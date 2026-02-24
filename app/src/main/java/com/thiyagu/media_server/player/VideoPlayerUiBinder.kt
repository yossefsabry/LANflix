package com.thiyagu.media_server.player

import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.thiyagu.media_server.subtitles.SubtitleMenuController

internal class VideoPlayerUiBinder(
    private val uiController: PlayerUiController,
    private val subtitleMenuController: SubtitleMenuController,
    private val debugOverlay: TextView,
    private val speedLabel: TextView?,
    private val titleLabel: TextView?,
    private val playerView: PlayerView,
    private val titleProvider: () -> String?
) {
    fun bind() {
        uiController.hideSystemUi()
        titleLabel?.text = titleProvider() ?: "Video"
        speedLabel?.setOnClickListener {
            uiController.showSpeedDialog()
        }
        uiController.updateSpeedLabel(1.0f)
        debugOverlay.setOnClickListener {
            uiController.toggleDebugOverlay()
        }
        playerView.setOnLongClickListener {
            uiController.toggleDebugOverlay()
            true
        }
    }

    fun onSettingsClicked() {
        uiController.showSettingsMenu {
            subtitleMenuController.showMenu()
        }
    }
}
