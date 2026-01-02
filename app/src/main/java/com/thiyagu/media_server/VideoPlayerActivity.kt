package com.thiyagu.media_server

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loadingIndicator: ProgressBar
    private var player: ExoPlayer? = null
    private var videoUrl: String? = null
    
    // Dependencies
    // Dependencies
    private val historyRepository: com.thiyagu.media_server.data.VideoHistoryRepository by inject()
    private val userPreferences: com.thiyagu.media_server.data.UserPreferences by inject()
    
    private var historyRetentionDays = 10 // Default
    private var isResumedFromHistory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.player_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        
        videoUrl = intent.getStringExtra("VIDEO_URL")

        if (videoUrl.isNullOrEmpty()) {
            finish()
            return
        }
        
        // Hide system UI for immersive mode
        hideSystemUi()
        
        // Initialize history settings
        lifecycleScope.launch {
            userPreferences.historyRetentionFlow.collect { days ->
                historyRetentionDays = days
                if (days != -1) {
                    historyRepository.pruneHistory(days)
                }
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
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    @OptIn(UnstableApi::class) 
    private fun initializePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(this)
                .build()
                .apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_BUFFERING) {
                                loadingIndicator.visibility = View.VISIBLE
                            } else {
                                loadingIndicator.visibility = View.GONE
                            }
                        }
                    })
                }
            
            playerView.player = player
        }

        val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
        player?.setMediaItem(mediaItem)
        
        // Resume Logic
        if (!isResumedFromHistory && historyRetentionDays != -1) {
            lifecycleScope.launch {
                val savedPosition = historyRepository.getPosition(videoUrl!!)
                if (savedPosition != null && savedPosition > 0) {
                    player?.seekTo(savedPosition)
                    android.widget.Toast.makeText(this@VideoPlayerActivity, "Resuming playback...", android.widget.Toast.LENGTH_SHORT).show()
                    isResumedFromHistory = true
                }
            }
        }
        
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            // Save position before releasing
            if (historyRetentionDays != -1 && videoUrl != null) {
                val position = exoPlayer.currentPosition
                val duration = exoPlayer.duration
                // Only save if not near the end (e.g., > 95% complete) or < 5s
                if (duration > 0 && position < (duration * 0.95)) {
                    lifecycleScope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            historyRepository.savePosition(videoUrl!!, position)
                        }
                    }
                }
            }
            exoPlayer.release()
        }
        player = null
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
}
