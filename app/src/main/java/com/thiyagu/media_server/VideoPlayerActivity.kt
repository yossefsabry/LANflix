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

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var debugOverlay: TextView
    private var player: ExoPlayer? = null
    private var videoUrl: String? = null
    private var debugJob: Job? = null
    
    // Video history for resume functionality
    private val videoHistoryRepository: com.thiyagu.media_server.data.VideoHistoryRepository by inject()

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.player_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        debugOverlay = findViewById(R.id.debug_overlay)

        videoUrl = intent.getStringExtra("VIDEO_URL")

        if (videoUrl.isNullOrEmpty()) {
            finish()
            return
        }

        hideSystemUi()
        
        // Tap to toggle debug
        playerView.setOnClickListener {
             if (debugOverlay.visibility == View.VISIBLE) {
                 debugOverlay.visibility = View.GONE
                 stopDebugUpdates()
             } else {
                 debugOverlay.visibility = View.VISIBLE
                 startDebugUpdates()
             }
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
        // Save playback position before releasing player
        lifecycleScope.launch {
            player?.let { p ->
                val position = p.currentPosition
                videoUrl?.let { url ->
                    videoHistoryRepository.savePosition(url, position)
                }
            }
        }
        releasePlayer()
        stopDebugUpdates()
    }

    override fun onStop() {
        super.onStop()
        // Save playback position before releasing player
        lifecycleScope.launch {
            player?.let { p ->
                val position = p.currentPosition
                videoUrl?.let { url ->
                    videoHistoryRepository.savePosition(url, position)
                }
            }
        }
        releasePlayer()
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
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("LANflix-App/2.0")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)

            player = ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttributes, true) // Handle focus
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                             updateDebugInfo()
                             when (playbackState) {
                                 Player.STATE_BUFFERING -> loadingIndicator.visibility = View.VISIBLE
                                 Player.STATE_READY -> loadingIndicator.visibility = View.GONE
                                 Player.STATE_ENDED -> { loadingIndicator.visibility = View.GONE; showFinishDialog() }
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
            videoUrl?.let { url ->
                val savedPosition = videoHistoryRepository.getPosition(url)
                savedPosition?.let { pos ->
                    player?.seekTo(pos)
                }
            }
        }
        
        player?.playWhenReady = true
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
}
