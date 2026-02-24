package com.thiyagu.media_server.cast

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal class CastUrlResolver {
    suspend fun resolve(
        videoUrl: String,
        pin: String?,
        clientId: String?
    ): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(videoUrl)
        val host = uri.host ?: return@withContext null
        val port = if (uri.port != -1) uri.port else DEFAULT_PORT
        val filename = uri.lastPathSegment ?: return@withContext null
        val pathParam = uri.getQueryParameter("path")

        val token = if (!pin.isNullOrEmpty()) {
            requestToken(
                host = host,
                port = port,
                filename = filename,
                pathParam = pathParam,
                clientId = clientId,
                pin = pin
            ) ?: return@withContext null
        } else {
            null
        }

        val builder = uri.buildUpon().clearQuery()
            .scheme("http")
            .authority("$host:$port")
        if (!pathParam.isNullOrEmpty()) {
            builder.appendQueryParameter("path", pathParam)
        }
        if (!clientId.isNullOrEmpty()) {
            builder.appendQueryParameter("client", clientId)
        }
        if (!token.isNullOrEmpty()) {
            builder.appendQueryParameter("token", token)
        }
        builder.build().toString()
    }

    private fun requestToken(
        host: String,
        port: Int,
        filename: String,
        pathParam: String?,
        clientId: String?,
        pin: String
    ): String? {
        val tokenUrl = Uri.Builder()
            .scheme("http")
            .authority("$host:$port")
            .appendPath("api")
            .appendPath("cast")
            .appendPath("token")
            .appendQueryParameter("filename", filename)
            .apply {
                if (!pathParam.isNullOrEmpty()) {
                    appendQueryParameter("path", pathParam)
                }
                if (!clientId.isNullOrEmpty()) {
                    appendQueryParameter("client", clientId)
                }
            }
            .build()
        return try {
            val connection =
                URL(tokenUrl.toString()).openConnection()
                    as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TOKEN_CONNECT_TIMEOUT_MS
            connection.readTimeout = TOKEN_READ_TIMEOUT_MS
            connection.setRequestProperty("X-Lanflix-Pin", pin)
            if (!clientId.isNullOrEmpty()) {
                connection.setRequestProperty(
                    "X-Lanflix-Client",
                    clientId
                )
            }
            val code = connection.responseCode
            val body = if (code == 200) {
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }
            } else {
                null
            }
            runCatching { connection.inputStream?.close() }
            connection.disconnect()
            if (body == null) return null
            val json = JSONObject(body)
            json.optString("token", null)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        private const val DEFAULT_PORT = 8888
        private const val TOKEN_CONNECT_TIMEOUT_MS = 2000
        private const val TOKEN_READ_TIMEOUT_MS = 2000
    }
}
