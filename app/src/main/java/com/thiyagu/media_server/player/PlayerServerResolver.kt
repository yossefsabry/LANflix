package com.thiyagu.media_server.player

import android.net.Uri
import android.os.SystemClock
import com.thiyagu.media_server.server.ServerDiscoveryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class PlayerServerResolver(
    private val discoveryManager: ServerDiscoveryManager,
    private val pinProvider: () -> String?,
    private val clientIdProvider: () -> String?,
    private val defaultPort: Int,
    private val rediscoveryTimeoutMs: Long,
    private val rediscoveryPollMs: Long,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int
) {
    suspend fun resolve(currentUrl: String): String? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(currentUrl)
            val filename =
                uri.lastPathSegment ?: return@withContext currentUrl
            val pathParam = uri.getQueryParameter("path")
            val effectivePin =
                pinProvider() ?: uri.getQueryParameter("pin")
            val client = clientIdProvider().orEmpty()
            val currentHost = uri.host
                ?: return@withContext currentUrl
            val currentPort =
                if (uri.port != -1) uri.port else defaultPort

            if (checkServerHasVideo(
                    currentHost,
                    currentPort,
                    filename,
                    pathParam,
                    effectivePin,
                    client
                )
            ) {
                return@withContext currentUrl
            }

            var discoveryStarted = false
            try {
                discoveryManager.startDiscovery()
                discoveryStarted = true
                val deadlineMs =
                    SystemClock.elapsedRealtime() +
                        rediscoveryTimeoutMs
                while (SystemClock.elapsedRealtime() < deadlineMs) {
                    val snapshot =
                        discoveryManager.discoveredServers.value
                    for (server in snapshot) {
                        if (checkServerHasVideo(
                                server.ip,
                                server.port,
                                filename,
                                pathParam,
                                effectivePin,
                                client
                            )
                        ) {
                            if (server.ip == currentHost &&
                                server.port == currentPort
                            ) {
                                return@withContext currentUrl
                            }
                            val updatedUri = uri.buildUpon()
                                .scheme("http")
                                .authority(
                                    "${server.ip}:${server.port}"
                                )
                                .build()
                            return@withContext updatedUri.toString()
                        }
                    }
                    delay(rediscoveryPollMs)
                }
            } finally {
                if (discoveryStarted) {
                    discoveryManager.stopDiscovery()
                }
            }

            currentUrl
        }

    private fun checkServerHasVideo(
        host: String,
        port: Int,
        filename: String,
        pathParam: String?,
        pinParam: String?,
        client: String
    ): Boolean {
        return try {
            val pathQuery = if (!pathParam.isNullOrEmpty()) {
                "?path=${URLEncoder.encode(pathParam, "UTF-8")}" 
            } else {
                ""
            }
            val url = URL(
                "http://$host:$port/api/exists/$filename" +
                    pathQuery
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            if (client.isNotEmpty()) {
                conn.setRequestProperty("X-Lanflix-Client", client)
            }
            if (!pinParam.isNullOrEmpty()) {
                conn.setRequestProperty("X-Lanflix-Pin", pinParam)
            }
            val code = conn.responseCode
            runCatching { conn.inputStream?.close() }
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }
}
