package com.thiyagu.media_server

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import com.thiyagu.media_server.player.PlayerHistoryController
import com.thiyagu.media_server.player.PlayerNetworkController
import com.thiyagu.media_server.player.PlayerUiController
import com.thiyagu.media_server.subtitles.SubtitleMenuController
import com.thiyagu.media_server.subtitles.SubtitleRepository
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var debugOverlay: TextView
    private var speedLabel: TextView? = null
    private var titleLabel: TextView? = null
    private var player: ExoPlayer? = null
    private var videoUrl: String? = null
    private var clientId: String? = null
    private var pin: String? = null
    private var historyKey: String? = null
    private lateinit var historyController: PlayerHistoryController
    private lateinit var networkController: PlayerNetworkController
    private lateinit var uiController: PlayerUiController
    private lateinit var subtitleMenuController: SubtitleMenuController
    private var pendingSeekPosition: Long? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    private var parserRecoveryAttempted = false
    private var keepAliveJob: Job? = null
    
    // Video history for resume functionality
    private val videoHistoryRepository: com.thiyagu.media_server.data.VideoHistoryRepository by inject()
    private val discoveryManager: com.thiyagu.media_server.server.ServerDiscoveryManager by inject()
    private val subtitleRepository: SubtitleRepository by inject()

    private val subtitlePickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val uri = result.data?.data
                ?: return@registerForActivityResult
            handleSubtitlePicked(uri)
        }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.player_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        debugOverlay = findViewById(R.id.debug_overlay)
        speedLabel = findViewById(R.id.lanflix_speed)
        titleLabel = findViewById(R.id.lanflix_title)
        playerView.setControllerShowTimeoutMs(3000)
        playerView.setControllerHideOnTouch(true)

        val uploadButton =
            playerView.findViewById<View>(
                R.id.lanflix_subtitle_upload
            )
        uploadButton?.setOnClickListener {
            openSubtitlePicker()
        }

        videoUrl = intent.getStringExtra("VIDEO_URL")
        clientId = intent.getStringExtra("CLIENT_ID")
        pin = intent.getStringExtra("PIN")
        historyKey = videoUrl?.let { sanitizeVideoUrl(it) }

        if (videoUrl.isNullOrEmpty()) {
            finish()
            return
        }

        networkController = PlayerNetworkController(lifecycleScope)
        historyController = PlayerHistoryController(
            scope = lifecycleScope,
            videoHistoryRepository = videoHistoryRepository,
            playerProvider = { player },
            historyKeyProvider = { historyKey },
            resumeMinPositionMs = RESUME_MIN_POSITION_MS,
            resumeSaveIntervalMs = RESUME_SAVE_INTERVAL_MS,
            resumeMinDeltaMs = RESUME_MIN_DELTA_MS,
            resumeEndGuardMs = RESUME_END_GUARD_MS
        )
        uiController = PlayerUiController(
            activity = this,
            scope = lifecycleScope,
            playerView = playerView,
            debugOverlay = debugOverlay,
            speedLabel = speedLabel,
            playerProvider = { player },
            videoUrlProvider = { videoUrl }
        )

        subtitleMenuController = SubtitleMenuController(
            activity = this,
            scope = lifecycleScope,
            playerProvider = { player },
            videoUrlProvider = { videoUrl },
            videoKeyProvider = { historyKey }
        )

        val settingsButton =
            playerView.findViewById<ImageButton>(
                R.id.exo_settings
            )
        settingsButton?.setOnClickListener {
            uiController.showSettingsMenu {
                subtitleMenuController.showMenu()
            }
        }

        uiController.hideSystemUi()

        titleLabel?.text = videoUrl?.let { deriveTitle(it) } ?: "Video"
        speedLabel?.setOnClickListener { uiController.showSpeedDialog() }
        uiController.updateSpeedLabel(1.0f)
        debugOverlay.setOnClickListener { uiController.toggleDebugOverlay() }

        // Long press toggles debug overlay; tap keeps default controller behavior.
        playerView.setOnLongClickListener {
            uiController.toggleDebugOverlay()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        networkController.resetDisconnectState()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        uiController.hideSystemUi()
        if (player == null) {
            initializePlayer()
        }
        if (debugOverlay.visibility == View.VISIBLE) uiController.startDebugUpdates()
    }

    override fun onPause() {
        super.onPause()
        historyController.persistPlaybackPosition()
        stopKeepAlive()
        releasePlayer()
        uiController.stopDebugUpdates()
        historyController.stopProgressUpdates()
    }

    override fun onStop() {
        super.onStop()
        networkController.notifyServerDisconnect(videoUrl, clientId, pin, DEFAULT_PORT)
        historyController.persistPlaybackPosition()
        stopKeepAlive()
        releasePlayer()
        historyController.stopProgressUpdates()
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        if (player == null) {
            // 1. Robust Renderers Factory (Force Hardware)
            val renderersFactory = DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

            // 2. Aggressive Load Control (Start fast, buffer plenty)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    3_000,  // Min buffer
                    15_000, // Max buffer (avoid long idle reads)
                    1_000,  // Buffer for playback start
                    2_000   // Buffer for rebuffer
                )
                .build()
                
            // 3. Audio Attributes (Correct Usage)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            // 4. Data Source (Handle Redirects)
            val headers = mutableMapOf<String, String>()
            clientId?.let { headers["X-Lanflix-Client"] = it }
            pin?.let { headers["X-Lanflix-Pin"] = it }
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("LANflix-App/2.0")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(0)
                .setReadTimeoutMs(0)
                .setDefaultRequestProperties(headers)

            player = ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttributes, true) // Handle focus
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
                .build()
                .apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                             uiController.updateDebugInfo()
                             when (playbackState) {
                                 Player.STATE_BUFFERING -> loadingIndicator.visibility = View.VISIBLE
                                 Player.STATE_READY -> {
                                     loadingIndicator.visibility = View.GONE
                                     retryCount = 0
                                     pendingSeekPosition?.let {
                                         historyController.maybeResumeFromPosition(it)
                                         pendingSeekPosition = null
                                     }
                                 }
                                 Player.STATE_ENDED -> {
                                     loadingIndicator.visibility = View.GONE
                                     historyController.clearResumePosition()
                                     showFinishDialog()
                                 }
                                 Player.STATE_IDLE -> loadingIndicator.visibility = View.GONE
                             }
                             updateKeepAlive()
                         }

                        override fun onPlayerError(error: PlaybackException) {
                            loadingIndicator.visibility = View.GONE
                            val httpError = error.cause as? HttpDataSource.InvalidResponseCodeException
                            val isOutOfRange = error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                                (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && httpError?.responseCode == 416)
                            if (isOutOfRange) {
                                recoverFromOutOfRange()
                                return
                            }
                            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED && !parserRecoveryAttempted) {
                                recoverFromMalformedContainer()
                                return
                            }
                            if (shouldRetry(error)) {
                                scheduleRetry()
                                return
                            }
                            val cause = error.cause?.message ?: "Unknown Cause"
                            val errorCode = error.errorCodeName
                            
                            AlertDialog.Builder(this@VideoPlayerActivity)
                                .setTitle("Playback Error")
                                .setMessage("Error: $errorCode\nDetails: ${error.message}\nCause: $cause\n\nURL: $videoUrl")
                                .setPositiveButton("Close") { _, _ -> finish() }
                                .setCancelable(false)
                                .show()
                        }
                        
                         override fun onVideoSizeChanged(videoSize: VideoSize) {
                            uiController.updateDebugInfo()
                         }

                         override fun onIsPlayingChanged(isPlaying: Boolean) {
                             updateKeepAlive()
                         }
                     })
                 }

            playerView.player = player
            playerView.keepScreenOn = true 
        }

        val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
        parserRecoveryAttempted = false
        player?.setMediaItem(mediaItem)
        player?.prepare()
        checkSubtitlesInBackground()

        // Restore saved playback position
        lifecycleScope.launch {
            historyKey?.let { key ->
                val savedPosition = videoHistoryRepository.getPosition(key)
                savedPosition?.let { pos ->
                    if (pos >= RESUME_MIN_POSITION_MS) {
                        pendingSeekPosition = pos
                        if (player?.playbackState == Player.STATE_READY) {
                            historyController.maybeResumeFromPosition(pos)
                            pendingSeekPosition = null
                        }
                    }
                }
            }
        }
        
        player?.playWhenReady = true
        historyController.startProgressUpdates()
    }

    private fun checkSubtitlesInBackground() {
        val key = historyKey ?: return
        val url = videoUrl ?: return
        val title = deriveTitle(url)
        val lang = Locale.getDefault().language
            .ifBlank { "en" }
        lifecycleScope.launch(Dispatchers.IO) {
            subtitleRepository.checkAndCache(
                key,
                title,
                lang
            )
        }
    }

    private fun openSubtitlePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "text/*",
                "application/octet-stream"
            )
        )
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        subtitlePickerLauncher.launch(intent)
    }

    private fun handleSubtitlePicked(uri: Uri) {
        val key = historyKey ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = subtitleRepository.saveUserSubtitle(
                key,
                uri
            )
            val messageId = if (saved) {
                R.string.subtitle_upload_done
            } else {
                R.string.subtitle_upload_failed
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@VideoPlayerActivity,
                    getString(messageId),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun releasePlayer() {
        retryJob?.cancel()
        retryJob = null
        stopKeepAlive()
        player?.release()
        player = null
        playerView.keepScreenOn = false
    }

    private fun updateKeepAlive() {
        val p = player ?: return
        val shouldRun = p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        if (shouldRun) {
            startKeepAlive()
        } else {
            stopKeepAlive()
        }
    }

    private fun startKeepAlive() {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = lifecycleScope.launch(Dispatchers.IO) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                sendKeepAlivePing()
                delay(KEEPALIVE_INTERVAL_MS)
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun sendKeepAlivePing() {
        val url = videoUrl ?: return
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val port = if (uri.port != -1) uri.port else DEFAULT_PORT
        val pingUrl = URL("http://$host:$port/api/ping")
        try {
            val connection = (pingUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = KEEPALIVE_CONNECT_TIMEOUT_MS
                readTimeout = KEEPALIVE_READ_TIMEOUT_MS
                clientId?.let { setRequestProperty("X-Lanflix-Client", it) }
                pin?.let { setRequestProperty("X-Lanflix-Pin", it) }
            }
            runCatching { connection.inputStream?.close() }
            connection.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun shouldRetry(error: PlaybackException): Boolean {
        if (retryCount >= MAX_RETRY_COUNT) return false
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> true
            else -> false
        }
    }

    private fun recoverFromOutOfRange() {
        pendingSeekPosition = null
        retryJob?.cancel()
        retryJob = null
        historyController.clearResumePosition()
        val p = player ?: return
        p.seekTo(0)
        p.prepare()
        p.playWhenReady = true
    }

    private fun recoverFromMalformedContainer() {
        parserRecoveryAttempted = true
        pendingSeekPosition = null
        retryJob?.cancel()
        retryJob = null
        historyController.clearResumePosition()
        val url = videoUrl ?: return
        val p = player ?: return
        p.stop()
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.playWhenReady = true
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryCount += 1
        val attempt = retryCount
        val retryDelayMs = (RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)))
            .coerceAtMost(RETRY_MAX_DELAY_MS)
        loadingIndicator.visibility = View.VISIBLE
        retryJob = lifecycleScope.launch {
            delay(retryDelayMs)
            val currentUrl = videoUrl
            if (!currentUrl.isNullOrEmpty()) {
                val resolvedUrl = resolveServerUrlForRetry(currentUrl)
                if (!resolvedUrl.isNullOrEmpty() && resolvedUrl != currentUrl) {
                    val resumePosition = player?.currentPosition ?: 0L
                    if (resumePosition > 0L) {
                        pendingSeekPosition = resumePosition
                    }
                    videoUrl = resolvedUrl
                    player?.setMediaItem(MediaItem.fromUri(Uri.parse(resolvedUrl)))
                }
            }
            val p = player ?: return@launch
            p.prepare()
            p.playWhenReady = true
        }
    }

    private suspend fun resolveServerUrlForRetry(currentUrl: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(currentUrl)
        val filename = uri.lastPathSegment ?: return@withContext currentUrl
        val pathParam = uri.getQueryParameter("path")
        val effectivePin = pin ?: uri.getQueryParameter("pin")
        val client = clientId.orEmpty()
        val currentHost = uri.host ?: return@withContext currentUrl
        val currentPort = if (uri.port != -1) uri.port else DEFAULT_PORT

        if (checkServerHasVideo(currentHost, currentPort, filename, pathParam, effectivePin, client)) {
            return@withContext currentUrl
        }

        var discoveryStarted = false
        try {
            discoveryManager.startDiscovery()
            discoveryStarted = true
            val deadlineMs = SystemClock.elapsedRealtime() + REDISCOVERY_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val snapshot = discoveryManager.discoveredServers.value
                for (server in snapshot) {
                    if (checkServerHasVideo(server.ip, server.port, filename, pathParam, effectivePin, client)) {
                        if (server.ip == currentHost && server.port == currentPort) {
                            return@withContext currentUrl
                        }
                        val updatedUri = uri.buildUpon()
                            .scheme("http")
                            .authority("${server.ip}:${server.port}")
                            .build()
                        return@withContext updatedUri.toString()
                    }
                }
                delay(REDISCOVERY_POLL_MS)
            }
        } finally {
            if (discoveryStarted) {
                discoveryManager.stopDiscovery()
            }
        }

        currentUrl
    }

    private fun checkServerHasVideo(
        host: String,
        port: Int,
        filename: String,
        pathParam: String?,
        pinParam: String?,
        client: String
    ): Boolean {
        return try {
            val pathQuery = if (!pathParam.isNullOrEmpty()) {
                "?path=${URLEncoder.encode(pathParam, "UTF-8")}" 
            } else {
                ""
            }
            val url = URL("http://$host:$port/api/exists/$filename$pathQuery")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = REDISCOVERY_CONNECT_TIMEOUT_MS
                readTimeout = REDISCOVERY_READ_TIMEOUT_MS
                if (client.isNotEmpty()) {
                    setRequestProperty("X-Lanflix-Client", client)
                }
                if (!pinParam.isNullOrEmpty()) {
                    setRequestProperty("X-Lanflix-Pin", pinParam)
                }
            }
            val code = conn.responseCode
            runCatching { conn.inputStream?.close() }
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun showFinishDialog() {
        AlertDialog.Builder(this)
            .setTitle("Playback Finished")
            .setPositiveButton("Replay") { _, _ ->
                player?.seekTo(0)
                player?.prepare()
            }
            .setNegativeButton("Close") { _, _ -> finish() }
            .show()
    }

    private fun sanitizeVideoUrl(url: String): String {
        val uri = Uri.parse(url)
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames
            .filter { it != "pin" && it != "client" }
            .forEach { key ->
                uri.getQueryParameters(key).forEach { value ->
                    builder.appendQueryParameter(key, value)
                }
            }
        return builder.build().toString()
    }

    private fun deriveTitle(url: String): String {
        val uri = Uri.parse(url)
        return uri.lastPathSegment?.replace("%20", " ") ?: "Video"
    }

    companion object {
        private const val DEFAULT_PORT = 8888
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val RESUME_MIN_POSITION_MS = 4_000L
        private const val RESUME_SAVE_INTERVAL_MS = 4_000L
        private const val RESUME_MIN_DELTA_MS = 2_000L
        private const val RESUME_END_GUARD_MS = 15_000L
        private const val MAX_RETRY_COUNT = 6
        private const val RETRY_BASE_DELAY_MS = 1500L
        private const val RETRY_MAX_DELAY_MS = 20_000L
        private const val REDISCOVERY_TIMEOUT_MS = 6_000L
        private const val REDISCOVERY_POLL_MS = 500L
        private const val REDISCOVERY_CONNECT_TIMEOUT_MS = 2000
        private const val REDISCOVERY_READ_TIMEOUT_MS = 2000
        private const val KEEPALIVE_INTERVAL_MS = 20_000L
        private const val KEEPALIVE_CONNECT_TIMEOUT_MS = 1500
        private const val KEEPALIVE_READ_TIMEOUT_MS = 1500
    }
}
