package com.thiyagu.media_server

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.thiyagu.media_server.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min
import kotlin.math.roundToLong

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val gson = Gson()
    private val prefs by lazy { getSharedPreferences(CLIENT_PREFS, MODE_PRIVATE) }

    private var host: String? = null
    private var port: Int = DEFAULT_PORT
    private var selectedVideo: VideoItem? = null
    private var cachedVideos: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnOpenClient.setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }
        binding.btnRefreshVideos.setOnClickListener { refreshVideos() }
        binding.btnSelectVideo.setOnClickListener { showVideoPicker() }
        binding.btnRunSeek.setOnClickListener { runSeekTest() }
        binding.btnRunThroughput.setOnClickListener { runThroughputTest() }
        binding.btnRunExists.setOnClickListener { runExistsTest() }
        binding.btnRefreshCache.setOnClickListener { refreshServerCache() }

        loadServerInfo()
        refreshVideos()
    }

    private fun loadServerInfo() {
        host = prefs.getString(LAST_SERVER_IP, null)
        port = prefs.getInt(LAST_SERVER_PORT, DEFAULT_PORT)
        val display = if (host.isNullOrBlank()) {
            "No server connected"
        } else {
            "http://$host:$port"
        }
        binding.serverStatus.text = display
        updateUiState()
    }

    private fun updateUiState() {
        val hasServer = !host.isNullOrBlank()
        binding.btnRefreshVideos.isEnabled = hasServer
        binding.btnSelectVideo.isEnabled = hasServer
        binding.btnRunSeek.isEnabled = hasServer
        binding.btnRunThroughput.isEnabled = hasServer
        binding.btnRunExists.isEnabled = hasServer
        binding.btnRefreshCache.isEnabled = hasServer
    }

    private fun updateSelectedVideoUi() {
        val label = selectedVideo?.let { "${it.name} (${formatSize(it.size)})" }
            ?: "No video selected"
        binding.selectedVideo.text = label
    }

    private fun refreshVideos() {
        if (host.isNullOrBlank()) return
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchCacheVideos() }
            cachedVideos = result
            if (cachedVideos.isNotEmpty()) {
                if (selectedVideo == null) {
                    selectedVideo = cachedVideos.first()
                }
                updateSelectedVideoUi()
            } else {
                Toast.makeText(this@DiagnosticsActivity, "No videos found", Toast.LENGTH_SHORT).show()
            }
            setBusy(false)
        }
    }

    private fun showVideoPicker() {
        if (cachedVideos.isEmpty()) {
            Toast.makeText(this, "No videos available", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = cachedVideos.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Video")
            .setItems(labels) { _, index ->
                selectedVideo = cachedVideos[index]
                updateSelectedVideoUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runSeekTest() {
        val video = selectedVideo
        if (!ensureVideo(video)) return
        val selected = video ?: return
        setBusy(true)
        binding.seekResult.text = "Running seek test..."
        lifecycleScope.launch {
            val output = withContext(Dispatchers.IO) {
                val size = resolveVideoSize(selected)
                val ranges = buildSeekRanges(size)
                val url = buildVideoUrl(selected)
                val results = ranges.map { range ->
                    measureTtfb(url, range)
                }
                val median = results.sorted()[results.size / 2]
                Pair(median, results)
            }

            val (medianMs, allResults) = output
            val detail = allResults.joinToString(" • ") { "${it}ms" }
            binding.seekResult.text = "Median: ${medianMs}ms (Samples: $detail)"
            setBusy(false)
        }
    }

    private fun runThroughputTest() {
        val video = selectedVideo
        if (!ensureVideo(video)) return
        val selected = video ?: return
        setBusy(true)
        binding.throughputResult.text = "Running throughput test..."
        lifecycleScope.launch {
            val mbps = withContext(Dispatchers.IO) {
                val size = resolveVideoSize(selected)
                val effectiveSize = if (size > 0) size else MIN_THROUGHPUT_BYTES
                val rangeSize = min(MAX_THROUGHPUT_BYTES, effectiveSize)
                val url = buildVideoUrl(selected)
                measureThroughput(url, 0L, rangeSize - 1)
            }

            val pass = mbps >= TARGET_MBPS
            val status = if (pass) "PASS" else "LOW"
            binding.throughputResult.text = "${"%.1f".format(mbps)} Mbps ($status, target ${TARGET_MBPS} Mbps)"
            setBusy(false)
        }
    }

    private fun runExistsTest() {
        val video = selectedVideo
        if (!ensureVideo(video)) return
        val selected = video ?: return
        setBusy(true)
        binding.existsResult.text = "Checking file existence..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val url = buildExistsUrl(selected)
                val (exists, durationMs) = measureExists(url)
                Pair(exists, durationMs)
            }
            val (exists, durationMs) = result
            val status = if (exists) "Found" else "Missing"
            binding.existsResult.text = "$status (${durationMs}ms). Move the file and rerun to validate refresh."
            setBusy(false)
        }
    }

    private fun refreshServerCache() {
        if (host.isNullOrBlank()) return
        setBusy(true)
        binding.existsResult.text = "Refreshing server cache..."
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { callRefreshCache() }
            binding.existsResult.text = if (ok) {
                "Cache refresh triggered. Rerun exists test."
            } else {
                "Cache refresh failed."
            }
            setBusy(false)
        }
    }

    private fun ensureVideo(video: VideoItem?): Boolean {
        if (host.isNullOrBlank()) {
            Toast.makeText(this, "Connect to a server first", Toast.LENGTH_SHORT).show()
            return false
        }
        if (video == null) {
            Toast.makeText(this, "Select a video first", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun setBusy(isBusy: Boolean) {
        binding.btnRefreshVideos.isEnabled = !isBusy && !host.isNullOrBlank()
        binding.btnSelectVideo.isEnabled = !isBusy && !host.isNullOrBlank()
        binding.btnRunSeek.isEnabled = !isBusy && !host.isNullOrBlank()
        binding.btnRunThroughput.isEnabled = !isBusy && !host.isNullOrBlank()
        binding.btnRunExists.isEnabled = !isBusy && !host.isNullOrBlank()
        binding.btnRefreshCache.isEnabled = !isBusy && !host.isNullOrBlank()
        binding.btnOpenClient.isEnabled = !isBusy
    }

    private fun buildVideoUrl(video: VideoItem): String {
        val base = "http://$host:$port/${URLEncoder.encode(video.name, "UTF-8")}" +
            "?path=${URLEncoder.encode(video.path, "UTF-8")}" 
        return appendAuthQuery(base, true)
    }

    private fun buildExistsUrl(video: VideoItem): String {
        val base = "http://$host:$port/api/exists/${URLEncoder.encode(video.name, "UTF-8")}" +
            "?path=${URLEncoder.encode(video.path, "UTF-8")}"
        return appendAuthQuery(base, true)
    }

    private fun appendAuthQuery(base: String, hasQuery: Boolean): String {
        val pin = getSavedPin()
        val clientId = getClientId()
        val parts = mutableListOf<String>()
        if (!pin.isNullOrBlank()) parts.add("pin=${URLEncoder.encode(pin, "UTF-8")}")
        if (!clientId.isNullOrBlank()) parts.add("client=${URLEncoder.encode(clientId, "UTF-8")}")
        if (parts.isEmpty()) return base
        val joiner = if (hasQuery) "&" else "?"
        return base + joiner + parts.joinToString("&")
    }

    private fun buildSeekRanges(size: Long): List<LongRange> {
        val safeSize = if (size > 0) size else MIN_THROUGHPUT_BYTES
        val offsets = listOf(0.05, 0.5, 0.9).map { (safeSize * it).roundToLong() }
        return offsets.map { start ->
            val s = start.coerceAtLeast(0)
            val e = (s + SEEK_SAMPLE_BYTES - 1).coerceAtMost(safeSize - 1)
            s..e
        }
    }

    private fun resolveVideoSize(video: VideoItem): Long {
        return if (video.size > 0) video.size else fetchExistsSize(video)
    }

    private fun fetchExistsSize(video: VideoItem): Long {
        return try {
            val url = buildExistsUrl(video)
            val connection = openConnection(url)
            connection.connect()
            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            val json = gson.fromJson(body, ExistsResponse::class.java)
            json.size ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun measureTtfb(url: String, range: LongRange): Long {
        val connection = openConnection(url)
        connection.setRequestProperty("Range", "bytes=${range.first}-${range.last}")
        val start = System.nanoTime()
        connection.connect()
        val buffer = ByteArray(64 * 1024)
        val stream = connection.inputStream
        stream.read(buffer)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        stream.close()
        connection.disconnect()
        return elapsed
    }

    private fun measureThroughput(url: String, startByte: Long, endByte: Long): Float {
        val connection = openConnection(url)
        connection.setRequestProperty("Range", "bytes=${startByte}-${endByte}")
        val start = System.nanoTime()
        connection.connect()
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        val stream = connection.inputStream
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
        }
        stream.close()
        connection.disconnect()
        val durationSec = (System.nanoTime() - start) / 1_000_000_000.0
        if (durationSec <= 0.0) return 0f
        return ((total * 8) / durationSec / 1_000_000.0).toFloat()
    }

    private fun measureExists(url: String): Pair<Boolean, Long> {
        val start = System.nanoTime()
        return try {
            val connection = openConnection(url)
            connection.connect()
            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            val json = gson.fromJson(body, ExistsResponse::class.java)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            Pair(json.exists == true, elapsed)
        } catch (_: Exception) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            Pair(false, elapsed)
        }
    }

    private fun callRefreshCache(): Boolean {
        return try {
            val url = appendAuthQuery("http://$host:$port/api/refresh-cache", false)
            val connection = openConnection(url)
            connection.connect()
            connection.inputStream.close()
            connection.disconnect()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchCacheVideos(): List<VideoItem> {
        return try {
            val url = appendAuthQuery("http://$host:$port/api/cache", false)
            val connection = openConnection(url)
            connection.connect()
            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            val cache = gson.fromJson(body, CacheResponse::class.java)
            cache.videos ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 20000
        val pin = getSavedPin()
        val clientId = getClientId()
        if (!pin.isNullOrBlank()) {
            connection.setRequestProperty("X-Lanflix-Pin", pin)
        }
        if (!clientId.isNullOrBlank()) {
            connection.setRequestProperty("X-Lanflix-Client", clientId)
        }
        return connection
    }

    private fun getSavedPin(): String? {
        val hostValue = host ?: return null
        return prefs.getString("${PIN_PREF_PREFIX}${hostValue}:${port}", null)
    }

    private fun getClientId(): String? {
        return prefs.getString(CLIENT_ID_PREF, null)
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "Unknown"
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "${"%.1f".format(gb)} GB"
            mb >= 1 -> "${"%.1f".format(mb)} MB"
            else -> "${"%.1f".format(kb)} KB"
        }
    }

    data class CacheResponse(
        val videos: List<VideoItem>?
    )

    data class VideoItem(
        val name: String,
        val size: Long,
        val lastModified: Long,
        val path: String,
        val documentId: String?
    )

    data class ExistsResponse(
        val exists: Boolean?,
        val size: Long?
    )

    companion object {
        private const val CLIENT_PREFS = "lanflix_client_prefs"
        private const val LAST_SERVER_IP = "last_server_ip"
        private const val LAST_SERVER_PORT = "last_server_port"
        private const val PIN_PREF_PREFIX = "server_pin_"
        private const val CLIENT_ID_PREF = "client_id"
        private const val DEFAULT_PORT = 8888
        private const val TARGET_MBPS = 11f
        private const val SEEK_SAMPLE_BYTES = 512 * 1024L
        private const val MAX_THROUGHPUT_BYTES = 16L * 1024 * 1024
        private const val MIN_THROUGHPUT_BYTES = 4L * 1024 * 1024
    }
}
