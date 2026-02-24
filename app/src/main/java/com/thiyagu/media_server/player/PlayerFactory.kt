package com.thiyagu.media_server.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

internal object PlayerFactory {
    private const val USER_AGENT = "LANflix-App/2.0"
    private const val MIN_BUFFER_MS = 3_000
    private const val MAX_BUFFER_MS = 15_000
    private const val START_BUFFER_MS = 1_000
    private const val REBUFFER_MS = 2_000

    @OptIn(UnstableApi::class)
    fun create(
        context: Context,
        clientId: String?,
        pin: String?,
        seekIncrementMs: Long,
        listener: Player.Listener
    ): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                START_BUFFER_MS,
                REBUFFER_MS
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val headers = mutableMapOf<String, String>()
        clientId?.let { headers["X-Lanflix-Client"] = it }
        pin?.let { headers["X-Lanflix-Pin"] = it }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(0)
            .setReadTimeoutMs(0)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            httpFactory
        )

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
            )
            .setSeekBackIncrementMs(seekIncrementMs)
            .setSeekForwardIncrementMs(seekIncrementMs)
            .build()
            .apply { addListener(listener) }
    }
}
