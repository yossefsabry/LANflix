package com.thiyagu.media_server.player

import android.net.Uri
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.thiyagu.media_server.data.VideoHistoryRepository
import com.thiyagu.media_server.server.ServerDiscoveryManager
import com.thiyagu.media_server.subtitles.SubtitleMenuController
import com.thiyagu.media_server.subtitles.SubtitleRepository

internal class VideoPlayerCoordinator(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val playerView: PlayerView,
    private val loadingIndicator: ProgressBar,
    private val debugOverlay: TextView,
    private val speedLabel: TextView?,
    private val titleLabel: TextView?,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val subtitleRepository: SubtitleRepository,
    private val discoveryManager: ServerDiscoveryManager,
    private val initialUrl: String,
    private val clientId: String?,
    private val pin: String?,
    private val historyKey: String?
) {
    private lateinit var session: VideoPlayerSession
    private val uiController = PlayerUiController(
        activity = activity,
        scope = scope,
        playerView = playerView,
        debugOverlay = debugOverlay,
        speedLabel = speedLabel,
        playerProvider = { session.player() },
        videoUrlProvider = { session.currentUrl() }
    )
    private val subtitleMenuController = SubtitleMenuController(
        activity = activity,
        scope = scope,
        playerProvider = { session.player() },
        videoUrlProvider = { session.currentUrl() },
        videoKeyProvider = { historyKey }
    )
    private val subtitleHandler = VideoPlayerSubtitleHandler(
        activity = activity,
        scope = scope,
        subtitleRepository = subtitleRepository,
        historyKeyProvider = { historyKey },
        urlProvider = { session.currentUrl() }
    )
    private val uiBinder = VideoPlayerUiBinder(
        uiController = uiController,
        subtitleMenuController = subtitleMenuController,
        debugOverlay = debugOverlay,
        speedLabel = speedLabel,
        titleLabel = titleLabel,
        playerView = playerView,
        titleProvider = {
            session.currentUrl()?.let { deriveTitle(it) }
        }
    )

    init {
        session = VideoPlayerSession(
            activity = activity,
            scope = scope,
            playerView = playerView,
            loadingIndicator = loadingIndicator,
            debugOverlay = debugOverlay,
            uiController = uiController,
            videoHistoryRepository = videoHistoryRepository,
            subtitleHandler = subtitleHandler,
            discoveryManager = discoveryManager,
            initialUrl = initialUrl,
            clientId = clientId,
            pin = pin,
            historyKey = historyKey
        )
    }

    fun bindUi() {
        uiBinder.bind()
    }

    fun onSettingsClicked() {
        uiBinder.onSettingsClicked()
    }

    fun onStart() {
        session.onStart()
    }

    fun onResume() {
        session.onResume()
    }

    fun onPause() {
        session.onPause()
    }

    fun onStop() {
        session.onStop()
    }

    fun handleSubtitlePicked(uri: Uri) {
        subtitleHandler.handleSubtitlePicked(uri)
    }

    fun setCastingActive(active: Boolean) {
        if (active) {
            speedLabel?.isEnabled = false
            speedLabel?.alpha = 0.4f
            uiController.stopDebugUpdates()
            debugOverlay.visibility = View.GONE
            playerView.setOnLongClickListener(null)
        } else {
            speedLabel?.isEnabled = true
            speedLabel?.alpha = 1.0f
            uiBinder.bind()
        }
    }

    fun player(): ExoPlayer? = session.player()

    fun currentUrl(): String? = session.currentUrl()
}
