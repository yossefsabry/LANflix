package com.thiyagu.media_server

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.math.abs

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
    private var debugJob: Job? = null
    private var progressJob: Job? = null
    private var pendingSeekPosition: Long? = null
    private var lastSavedPosition: Long = 0L
    
    // Video history for resume functionality
    private val videoHistoryRepository: com.thiyagu.media_server.data.VideoHistoryRepository by inject()

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

        videoUrl = intent.getStringExtra("VIDEO_URL")
        clientId = intent.getStringExtra("CLIENT_ID")
        pin = intent.getStringExtra("PIN")
        historyKey = videoUrl?.let { sanitizeVideoUrl(it) }

        if (videoUrl.isNullOrEmpty()) {
            finish()
            return
        }

        hideSystemUi()

        titleLabel?.text = videoUrl?.let { deriveTitle(it) } ?: "Video"
        speedLabel?.setOnClickListener { showSpeedDialog() }
        updateSpeedLabel(1.0f)
        debugOverlay.setOnClickListener { toggleDebugOverlay() }

        // Long press toggles debug overlay; tap keeps default controller behavior.
        playerView.setOnLongClickListener {
            toggleDebugOverlay()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (player == null) {
            initializePlayer()
        }
        if (debugOverlay.visibility == View.VISIBLE) startDebugUpdates()
    }

    override fun onPause() {
        super.onPause()
        persistPlaybackPosition()
        releasePlayer()
        stopDebugUpdates()
        stopProgressUpdates()
    }

    override fun onStop() {
        super.onStop()
        persistPlaybackPosition()
        releasePlayer()
        stopProgressUpdates()
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
                    50_000, // Max buffer
                    1_500,  // Buffer for playback start
                    3_000   // Buffer for rebuffer
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
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
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
                             updateDebugInfo()
                             when (playbackState) {
                                 Player.STATE_BUFFERING -> loadingIndicator.visibility = View.VISIBLE
                                 Player.STATE_READY -> {
                                     loadingIndicator.visibility = View.GONE
                                     pendingSeekPosition?.let {
                                         maybeResumeFromPosition(it)
                                         pendingSeekPosition = null
                                     }
                                 }
                                 Player.STATE_ENDED -> {
                                     loadingIndicator.visibility = View.GONE
                                     clearResumePosition()
                                     showFinishDialog()
                                 }
                                 Player.STATE_IDLE -> loadingIndicator.visibility = View.GONE
                             }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            loadingIndicator.visibility = View.GONE
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
                            updateDebugInfo()
                        }
                    })
                }

            playerView.player = player
            playerView.keepScreenOn = true 
        }

        val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
        player?.setMediaItem(mediaItem)
        player?.prepare()

        // Restore saved playback position
        lifecycleScope.launch {
            historyKey?.let { key ->
                val savedPosition = videoHistoryRepository.getPosition(key)
                savedPosition?.let { pos ->
                    if (pos >= RESUME_MIN_POSITION_MS) {
                        pendingSeekPosition = pos
                        if (player?.playbackState == Player.STATE_READY) {
                            maybeResumeFromPosition(pos)
                            pendingSeekPosition = null
                        }
                    }
                }
            }
        }
        
        player?.playWhenReady = true
        startProgressUpdates()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.keepScreenOn = false
    }

    @OptIn(UnstableApi::class)
    private fun hideSystemUi() {
        playerView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LOW_PROFILE
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
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

    private fun toggleDebugOverlay() {
        if (debugOverlay.visibility == View.VISIBLE) {
            debugOverlay.visibility = View.GONE
            stopDebugUpdates()
        } else {
            debugOverlay.visibility = View.VISIBLE
            startDebugUpdates()
        }
    }
    
    private fun startDebugUpdates() {
        if (debugJob?.isActive == true) return
        debugJob = lifecycleScope.launch {
            while (true) {
                updateDebugInfo()
                delay(1000)
            }
        }
    }
    
    private fun stopDebugUpdates() {
        debugJob?.cancel()
        debugJob = null
    }

    private fun startProgressUpdates() {
        if (progressJob?.isActive == true) return
        progressJob = lifecycleScope.launch {
            while (true) {
                delay(RESUME_SAVE_INTERVAL_MS)
                val p = player ?: continue
                if (!p.isPlaying) continue
                val position = p.currentPosition
                val duration = p.duration
                val key = historyKey ?: continue

                if (position < RESUME_MIN_POSITION_MS) continue
                if (duration > 0 && duration - position <= RESUME_END_GUARD_MS) {
                    videoHistoryRepository.clearPosition(key)
                    lastSavedPosition = 0L
                    continue
                }

                if (abs(position - lastSavedPosition) >= RESUME_MIN_DELTA_MS) {
                    videoHistoryRepository.savePosition(key, position)
                    lastSavedPosition = position
                }
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun persistPlaybackPosition() {
        val p = player ?: return
        val key = historyKey ?: return
        val position = p.currentPosition
        val duration = p.duration
        lifecycleScope.launch {
            if (duration > 0 && duration - position <= RESUME_END_GUARD_MS) {
                videoHistoryRepository.clearPosition(key)
            } else {
                videoHistoryRepository.savePosition(key, position)
            }
        }
    }

    private fun clearResumePosition() {
        val key = historyKey ?: return
        lifecycleScope.launch {
            videoHistoryRepository.clearPosition(key)
        }
    }

    private fun maybeResumeFromPosition(position: Long) {
        val p = player ?: return
        val duration = p.duration
        if (duration > 0 && duration - position <= RESUME_END_GUARD_MS) {
            clearResumePosition()
            return
        }
        p.seekTo(position)
    }

    private fun showSpeedDialog() {
        val p = player ?: return
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
        val currentSpeed = p.playbackParameters.speed
        val currentIndex = speeds.indexOfFirst { abs(it - currentSpeed) < 0.01f }.coerceAtLeast(2)

        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val speed = speeds[which]
                p.playbackParameters = PlaybackParameters(speed)
                updateSpeedLabel(speed)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSpeedLabel(speed: Float) {
        val label = if (abs(speed - 1.0f) < 0.01f) "1x" else "${speed}x"
        speedLabel?.text = label
    }
    
    @OptIn(UnstableApi::class)
    private fun updateDebugInfo() {
        if (debugOverlay.visibility != View.VISIBLE) return
        val p = player ?: return
        
        val state = when(p.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
        
        val videoFormat = p.videoFormat
        val decoder = videoFormat?.codecs ?: "Unknown" // Fallback to codecs string if available, or just use mime type
        val buffered = p.bufferedPercentage
        val pos = p.currentPosition / 1000
        val dur = p.duration / 1000
        
        val text = """
            [DIAGNOSTICS]
            State: $state
            URL: $videoUrl
            Res: ${videoFormat?.width}x${videoFormat?.height} @ ${videoFormat?.frameRate}fps
            Video Mime: ${videoFormat?.sampleMimeType}
            Codecs: $decoder
            Audio Codec: ${p.audioFormat?.sampleMimeType}
            Position: ${pos}s / ${dur}s
            Buffered: $buffered%
            PlayWhenReady: ${p.playWhenReady}
            Audio Focus: ${if (p.volume > 0) "Active" else "Muted/Lost"}
            Speed: ${p.playbackParameters.speed}x
        """.trimIndent()
        
        debugOverlay.text = text
    }

    companion object {
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val RESUME_MIN_POSITION_MS = 4_000L
        private const val RESUME_SAVE_INTERVAL_MS = 4_000L
        private const val RESUME_MIN_DELTA_MS = 2_000L
        private const val RESUME_END_GUARD_MS = 15_000L
    }
}
