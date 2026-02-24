package com.thiyagu.media_server.player

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.thiyagu.media_server.cast.CastUrlResolver
import kotlinx.coroutines.launch

internal data class CastState(
    val isCasting: Boolean,
    val deviceName: String?
)

internal class CastPlaybackController(
    context: Context,
    private val scope: LifecycleCoroutineScope,
    private val playerView: PlayerView,
    private val localPlayerProvider: () -> ExoPlayer?,
    private val urlProvider: () -> String?,
    private val titleProvider: () -> String?,
    private val pinProvider: () -> String?,
    private val clientIdProvider: () -> String?,
    private val castUrlResolver: CastUrlResolver,
    private val onStateChanged: (CastState) -> Unit,
    private val onError: (String) -> Unit
) {
    private val castContext = CastContext.getSharedInstance(context)
    private val sessionManager: SessionManager =
        castContext.sessionManager
    private val castPlayer = CastPlayer(castContext)
    private var isCasting = false
    private val sessionListener =
        object : SessionManagerListener<Session> {
            override fun onSessionStarted(
                session: Session,
                sessionId: String
            ) {
                startCasting()
            }

            override fun onSessionResumed(
                session: Session,
                wasSuspended: Boolean
            ) {
                startCasting()
            }

            override fun onSessionEnded(
                session: Session,
                error: Int
            ) {
                stopCasting()
            }

            override fun onSessionSuspended(
                session: Session,
                reason: Int
            ) {
                stopCasting()
            }

            override fun onSessionStarting(session: Session) {}
            override fun onSessionStartFailed(
                session: Session,
                error: Int
            ) {
                stopCasting()
            }

            override fun onSessionEnding(session: Session) {}
            override fun onSessionResuming(
                session: Session,
                sessionId: String
            ) {}
            override fun onSessionResumeFailed(
                session: Session,
                error: Int
            ) {
                stopCasting()
            }
        }

    init {
        sessionManager.addSessionManagerListener(
            sessionListener,
            Session::class.java
        )
    }

    fun release() {
        sessionManager.removeSessionManagerListener(
            sessionListener,
            Session::class.java
        )
        castPlayer.release()
    }

    fun refresh() {
        val session = sessionManager.currentCastSession
        if (session != null && session.isConnected) {
            startCasting()
        }
    }

    private fun startCasting() {
        if (isCasting) return
        val localPlayer = localPlayerProvider() ?: return
        val url = urlProvider() ?: return
        val position = localPlayer.currentPosition
        localPlayer.pause()
        scope.launch {
            val castUrl = castUrlResolver.resolve(
                videoUrl = url,
                pin = pinProvider(),
                clientId = clientIdProvider()
            )
            if (castUrl.isNullOrEmpty()) {
                onError("Casting failed. Check PIN and Wi-Fi.")
                return@launch
            }
            val title = titleProvider() ?: "LANflix"
            val item = MediaItem.Builder()
                .setUri(castUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(title).build()
                )
                .build()
            castPlayer.setMediaItem(item, position)
            castPlayer.prepare()
            castPlayer.playWhenReady = true
            playerView.player = castPlayer
            isCasting = true
            onStateChanged(
                CastState(true, currentDeviceName())
            )
        }
    }

    private fun stopCasting() {
        if (!isCasting) return
        val localPlayer = localPlayerProvider()
        val position = castPlayer.currentPosition
        playerView.player = localPlayer
        if (localPlayer != null) {
            localPlayer.seekTo(position)
            localPlayer.playWhenReady = true
        }
        isCasting = false
        onStateChanged(CastState(false, null))
    }

    private fun currentDeviceName(): String? {
        val session =
            sessionManager.currentCastSession as? CastSession
        return session?.castDevice?.friendlyName
    }
}
