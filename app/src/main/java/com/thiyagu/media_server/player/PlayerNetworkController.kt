package com.thiyagu.media_server.player

import android.net.Uri
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class PlayerNetworkController(
    private val scope: LifecycleCoroutineScope
) {
    private var disconnectSent = false

    fun resetDisconnectState() {
        disconnectSent = false
    }

    fun notifyServerDisconnect(videoUrl: String?, clientId: String?, pin: String?, defaultPort: Int) {
        if (disconnectSent) return
        disconnectSent = true
        val url = videoUrl ?: return
        val host = Uri.parse(url).host ?: return
        val port = Uri.parse(url).port.let { if (it != -1) it else defaultPort }
        val client = clientId ?: ""
        val query = if (client.isNotEmpty()) {
            "?client=${URLEncoder.encode(client, "UTF-8")}" 
        } else {
            ""
        }

        scope.launch(Dispatchers.IO) {
            try {
                val disconnectUrl = URL("http://$host:$port/api/bye$query")
                val connection = disconnectUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 1500
                connection.readTimeout = 1500
                if (client.isNotEmpty()) {
                    connection.setRequestProperty("X-Lanflix-Client", client)
                }
                if (!pin.isNullOrEmpty()) {
                    connection.setRequestProperty("X-Lanflix-Pin", pin)
                }
                runCatching { connection.inputStream?.close() }
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
