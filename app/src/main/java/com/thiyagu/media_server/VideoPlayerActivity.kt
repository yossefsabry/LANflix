package com.thiyagu.media_server

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.thiyagu.media_server.cast.CastUrlResolver
import com.thiyagu.media_server.player.CastPlaybackController
import com.thiyagu.media_server.player.CastState
import com.thiyagu.media_server.player.VideoPlayerCoordinator
import com.thiyagu.media_server.player.deriveTitle
import com.thiyagu.media_server.player.sanitizeVideoUrl
import com.thiyagu.media_server.data.VideoHistoryRepository
import com.thiyagu.media_server.server.ServerDiscoveryManager
import com.thiyagu.media_server.subtitles.SubtitleRepository
import org.koin.android.ext.android.inject

class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var debugOverlay: TextView
    private var speedLabel: TextView? = null
    private var titleLabel: TextView? = null
    private var castStatusBanner: TextView? = null
    private var settingsButton: ImageButton? = null
    private lateinit var coordinator: VideoPlayerCoordinator
    private var castController: CastPlaybackController? = null
    private var clientId: String? = null
    private var pin: String? = null

    private val videoHistoryRepository:
        VideoHistoryRepository by inject()
    private val discoveryManager: ServerDiscoveryManager by inject()
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
            coordinator.handleSubtitlePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.player_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        debugOverlay = findViewById(R.id.debug_overlay)
        speedLabel = findViewById(R.id.lanflix_speed)
        titleLabel = findViewById(R.id.lanflix_title)
        castStatusBanner = findViewById(R.id.cast_status_banner)
        playerView.setControllerShowTimeoutMs(3000)
        playerView.setControllerHideOnTouch(true)

        val videoUrl = intent.getStringExtra("VIDEO_URL")
        if (videoUrl.isNullOrEmpty()) {
            finish()
            return
        }
        clientId = intent.getStringExtra("CLIENT_ID")
        pin = intent.getStringExtra("PIN")
        val historyKey = sanitizeVideoUrl(videoUrl)

        coordinator = VideoPlayerCoordinator(
            activity = this,
            scope = lifecycleScope,
            playerView = playerView,
            loadingIndicator = loadingIndicator,
            debugOverlay = debugOverlay,
            speedLabel = speedLabel,
            titleLabel = titleLabel,
            videoHistoryRepository = videoHistoryRepository,
            subtitleRepository = subtitleRepository,
            discoveryManager = discoveryManager,
            initialUrl = videoUrl,
            clientId = clientId,
            pin = pin,
            historyKey = historyKey
        )

        playerView.findViewById<View>(
            R.id.lanflix_subtitle_upload
        )?.setOnClickListener {
            openSubtitlePicker()
        }
        settingsButton = playerView.findViewById<ImageButton>(
            R.id.exo_settings
        )
        settingsButton?.setOnClickListener {
            coordinator.onSettingsClicked()
        }
        setupCast()
        coordinator.bindUi()
    }

    override fun onStart() {
        super.onStart()
        coordinator.onStart()
        castController?.refresh()
    }

    override fun onResume() {
        super.onResume()
        coordinator.onResume()
    }

    override fun onPause() {
        super.onPause()
        coordinator.onPause()
    }

    override fun onStop() {
        super.onStop()
        coordinator.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        castController?.release()
    }

    private fun openSubtitlePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/x-subrip"
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/x-subrip",
                "text/plain"
            )
        )
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        subtitlePickerLauncher.launch(intent)
    }

    private fun setupCast() {
        val castButton =
            playerView.findViewById<MediaRouteButton>(
                R.id.lanflix_cast_button
            )
        if (castButton != null) {
            CastButtonFactory.setUpMediaRouteButton(this, castButton)
        }
        castController = CastPlaybackController(
            context = this,
            scope = lifecycleScope,
            playerView = playerView,
            localPlayerProvider = { coordinator.player() },
            urlProvider = { coordinator.currentUrl() },
            titleProvider = {
                coordinator.currentUrl()?.let { deriveTitle(it) }
            },
            pinProvider = { pin },
            clientIdProvider = { clientId },
            castUrlResolver = CastUrlResolver(),
            onStateChanged = { state -> updateCastUi(state) },
            onError = { message -> showCastError(message) }
        )
    }

    private fun updateCastUi(state: CastState) {
        if (state.isCasting) {
            val name = state.deviceName ?: "Cast"
            castStatusBanner?.text = "Casting to $name"
            castStatusBanner?.visibility = View.VISIBLE
            settingsButton?.isEnabled = false
            settingsButton?.alpha = 0.4f
            coordinator.setCastingActive(true)
        } else {
            castStatusBanner?.visibility = View.GONE
            settingsButton?.isEnabled = true
            settingsButton?.alpha = 1.0f
            coordinator.setCastingActive(false)
        }
    }

    private fun showCastError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
