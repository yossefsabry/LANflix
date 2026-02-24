package com.thiyagu.media_server.player

import android.net.Uri
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

internal class PlayerKeepAliveController(
    private val scope: LifecycleCoroutineScope,
    private val urlProvider: () -> String?,
    private val clientIdProvider: () -> String?,
    private val pinProvider: () -> String?,
    private val defaultPort: Int,
    private val intervalMs: Long,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int
) {
    private var keepAliveJob: Job? = null

    fun update(player: Player?) {
        val shouldRun = player?.playWhenReady == true &&
            (player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING)
        if (shouldRun) {
            start()
        } else {
            stop()
        }
    }

    fun stop() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun start() {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                sendPing()
                delay(intervalMs)
            }
        }
    }

    private fun sendPing() {
        val url = urlProvider() ?: return
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val port = if (uri.port != -1) uri.port else defaultPort
        val pingUrl = URL("http://$host:$port/api/ping")
        try {
            val connection = pingUrl.openConnection()
                as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            clientIdProvider()?.let {
                connection.setRequestProperty("X-Lanflix-Client", it)
            }
            pinProvider()?.let {
                connection.setRequestProperty("X-Lanflix-Pin", it)
            }
            runCatching { connection.inputStream?.close() }
            connection.disconnect()
        } catch (_: Exception) {
        }
    }
}
