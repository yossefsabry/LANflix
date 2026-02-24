package com.thiyagu.media_server.player

import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.thiyagu.media_server.data.VideoHistoryRepository
import com.thiyagu.media_server.server.ServerDiscoveryManager
import com.thiyagu.media_server.subtitles.buildMediaItemWithSubtitles
import kotlinx.coroutines.launch

internal class VideoPlayerSession(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val playerView: PlayerView,
    private val loadingIndicator: ProgressBar,
    private val debugOverlay: TextView,
    private val uiController: PlayerUiController,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val subtitleHandler: VideoPlayerSubtitleHandler,
    private val discoveryManager: ServerDiscoveryManager,
    initialUrl: String,
    private val clientId: String?,
    private val pin: String?,
    private val historyKey: String?
) {
    private var videoUrl: String? = initialUrl
    private var player: ExoPlayer? = null
    private val networkController = PlayerNetworkController(scope)
    private val historyController = PlayerHistoryController(
        scope = scope,
        videoHistoryRepository = videoHistoryRepository,
        playerProvider = { player },
        historyKeyProvider = { historyKey },
        resumeMinPositionMs =
            VideoPlayerDefaults.RESUME_MIN_POSITION_MS,
        resumeSaveIntervalMs =
            VideoPlayerDefaults.RESUME_SAVE_INTERVAL_MS,
        resumeMinDeltaMs = VideoPlayerDefaults.RESUME_MIN_DELTA_MS,
        resumeEndGuardMs = VideoPlayerDefaults.RESUME_END_GUARD_MS
    )
    private val retryController = PlayerRetryController(
        scope = scope,
        discoveryManager = discoveryManager,
        playerProvider = { player },
        urlProvider = { videoUrl },
        setUrl = { videoUrl = it },
        historyController = historyController,
        pinProvider = { pin },
        clientIdProvider = { clientId },
        defaultPort = VideoPlayerDefaults.DEFAULT_PORT,
        maxRetryCount = VideoPlayerDefaults.MAX_RETRY_COUNT,
        retryBaseDelayMs = VideoPlayerDefaults.RETRY_BASE_DELAY_MS,
        retryMaxDelayMs = VideoPlayerDefaults.RETRY_MAX_DELAY_MS,
        rediscoveryTimeoutMs =
            VideoPlayerDefaults.REDISCOVERY_TIMEOUT_MS,
        rediscoveryPollMs = VideoPlayerDefaults.REDISCOVERY_POLL_MS,
        connectTimeoutMs =
            VideoPlayerDefaults.REDISCOVERY_CONNECT_TIMEOUT_MS,
        readTimeoutMs =
            VideoPlayerDefaults.REDISCOVERY_READ_TIMEOUT_MS
    )
    private val keepAliveController = PlayerKeepAliveController(
        scope = scope,
        urlProvider = { videoUrl },
        clientIdProvider = { clientId },
        pinProvider = { pin },
        defaultPort = VideoPlayerDefaults.DEFAULT_PORT,
        intervalMs = VideoPlayerDefaults.KEEPALIVE_INTERVAL_MS,
        connectTimeoutMs =
            VideoPlayerDefaults.KEEPALIVE_CONNECT_TIMEOUT_MS,
        readTimeoutMs = VideoPlayerDefaults.KEEPALIVE_READ_TIMEOUT_MS
    )
    private val playerListener = PlayerEventListener(
        uiController = uiController,
        loadingIndicator = loadingIndicator,
        historyController = historyController,
        retryController = retryController,
        keepAliveController = keepAliveController,
        playerProvider = { player },
        onPlaybackEnded = {
            PlayerDialogs.showFinishDialog(
                activity,
                playerProvider = { player },
                onClose = { activity.finish() }
            )
        },
        onUnhandledError = { error ->
            PlayerDialogs.showPlaybackError(
                activity,
                error = error,
                urlProvider = { videoUrl },
                onClose = { activity.finish() }
            )
        }
    )

    fun onStart() {
        networkController.resetDisconnectState()
        initializePlayer()
    }

    fun onResume() {
        uiController.hideSystemUi()
        if (player == null) {
            initializePlayer()
        }
        if (debugOverlay.visibility == android.view.View.VISIBLE) {
            uiController.startDebugUpdates()
        }
    }

    fun onPause() {
        historyController.persistPlaybackPosition()
        releasePlayer()
        uiController.stopDebugUpdates()
        historyController.stopProgressUpdates()
    }

    fun onStop() {
        networkController.notifyServerDisconnect(
            videoUrl,
            clientId,
            pin,
            VideoPlayerDefaults.DEFAULT_PORT
        )
        historyController.persistPlaybackPosition()
        releasePlayer()
        historyController.stopProgressUpdates()
    }

    fun player(): ExoPlayer? = player

    fun currentUrl(): String? = videoUrl

    private fun initializePlayer() {
        if (player != null) return
        player = PlayerFactory.create(
            context = activity,
            clientId = clientId,
            pin = pin,
            seekIncrementMs = VideoPlayerDefaults.SEEK_INCREMENT_MS,
            listener = playerListener
        )
        playerView.player = player
        playerView.keepScreenOn = true
        retryController.resetForNewMedia()

        val url = videoUrl ?: return
        val mediaItem = buildMediaItemWithSubtitles(
            context = activity,
            videoKey = historyKey,
            url = url
        )
        player?.setMediaItem(mediaItem)
        player?.prepare()
        subtitleHandler.checkSubtitlesInBackground()
        restoreSavedPosition()
        player?.playWhenReady = true
        historyController.startProgressUpdates()
    }

    private fun restoreSavedPosition() {
        scope.launch {
            val key = historyKey ?: return@launch
            val saved = videoHistoryRepository.getPosition(key)
            if (saved != null &&
                saved >= VideoPlayerDefaults.RESUME_MIN_POSITION_MS
            ) {
                retryController.setPendingSeek(saved)
                if (player?.playbackState == Player.STATE_READY) {
                    retryController.onPlayerReady()
                }
            }
        }
    }

    private fun releasePlayer() {
        retryController.cancel()
        keepAliveController.stop()
        player?.release()
        player = null
        playerView.keepScreenOn = false
    }
}
