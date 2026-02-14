package com.thiyagu.media_server.player

import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class PlayerUiController(
    private val activity: AppCompatActivity,
    private val scope: LifecycleCoroutineScope,
    private val playerView: PlayerView,
    private val debugOverlay: TextView,
    private val speedLabel: TextView?,
    private val playerProvider: () -> ExoPlayer?,
    private val videoUrlProvider: () -> String?
) {
    private var debugJob: Job? = null

    fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, playerView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun toggleDebugOverlay() {
        if (debugOverlay.visibility == View.VISIBLE) {
            debugOverlay.visibility = View.GONE
            stopDebugUpdates()
        } else {
            debugOverlay.visibility = View.VISIBLE
            startDebugUpdates()
        }
    }

    fun startDebugUpdates() {
        if (debugJob?.isActive == true) return
        debugJob = scope.launch {
            while (true) {
                updateDebugInfo()
                delay(1000)
            }
        }
    }

    fun stopDebugUpdates() {
        debugJob?.cancel()
        debugJob = null
    }

    fun showSpeedDialog() {
        val p = playerProvider() ?: return
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
        val currentSpeed = p.playbackParameters.speed
        val currentIndex = speeds.indexOfFirst { abs(it - currentSpeed) < 0.01f }.coerceAtLeast(2)

        AlertDialog.Builder(activity)
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

    fun updateSpeedLabel(speed: Float) {
        val label = if (abs(speed - 1.0f) < 0.01f) "1x" else "${speed}x"
        speedLabel?.text = label
    }

    @OptIn(UnstableApi::class)
    fun updateDebugInfo() {
        if (debugOverlay.visibility != View.VISIBLE) return
        val p = playerProvider() ?: return

        val state = when (p.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }

        val videoFormat = p.videoFormat
        val decoder = videoFormat?.codecs ?: "Unknown"
        val buffered = p.bufferedPercentage
        val pos = p.currentPosition / 1000
        val dur = p.duration / 1000

        val text = """
            [DIAGNOSTICS]
            State: $state
            URL: ${videoUrlProvider()}
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
