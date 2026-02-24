package com.thiyagu.media_server.player

import android.view.View
import android.widget.ProgressBar
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

internal class PlayerEventListener(
    private val uiController: PlayerUiController,
    private val loadingIndicator: ProgressBar,
    private val historyController: PlayerHistoryController,
    private val retryController: PlayerRetryController,
    private val keepAliveController: PlayerKeepAliveController,
    private val playerProvider: () -> Player?,
    private val onPlaybackEnded: () -> Unit,
    private val onUnhandledError: (PlaybackException) -> Unit
) : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
        uiController.updateDebugInfo()
        when (playbackState) {
            Player.STATE_BUFFERING ->
                loadingIndicator.visibility = View.VISIBLE
            Player.STATE_READY -> {
                loadingIndicator.visibility = View.GONE
                retryController.onPlayerReady()
            }
            Player.STATE_ENDED -> {
                loadingIndicator.visibility = View.GONE
                historyController.clearResumePosition()
                onPlaybackEnded()
            }
            Player.STATE_IDLE ->
                loadingIndicator.visibility = View.GONE
        }
        keepAliveController.update(playerProvider())
    }

    override fun onPlayerError(error: PlaybackException) {
        loadingIndicator.visibility = View.GONE
        val handled = retryController.handleError(error)
        if (!handled) {
            onUnhandledError(error)
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        uiController.updateDebugInfo()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        keepAliveController.update(playerProvider())
    }
}
