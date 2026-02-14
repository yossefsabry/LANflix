package com.thiyagu.media_server.client

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread

internal class ClientConnectionController(
    private val prefs: SharedPreferences,
    private val defaultPort: Int,
    private val pinPrefPrefix: String,
    private val clientIdPref: String,
    private val lastServerIpKey: String,
    private val lastServerPortKey: String
) {

    fun getClientId(): String {
        val existing = prefs.getString(clientIdPref, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(clientIdPref, newId).apply()
        return newId
    }

    fun getSavedPin(ip: String, port: Int): String? {
        return prefs.getString("$pinPrefPrefix$ip:$port", null)
    }

    fun savePin(ip: String, port: Int, pin: String) {
        prefs.edit().putString("$pinPrefPrefix$ip:$port", pin).apply()
    }

    fun clearSavedPin(ip: String, port: Int) {
        prefs.edit().remove("$pinPrefPrefix$ip:$port").apply()
    }

    fun saveLastServer(ip: String, port: Int) {
        prefs.edit()
            .putString(lastServerIpKey, ip)
            .putInt(lastServerPortKey, port)
            .apply()
    }

    fun clearLastServer() {
        prefs.edit()
            .remove(lastServerIpKey)
            .remove(lastServerPortKey)
            .apply()
    }

    fun getLastServer(): Pair<String, Int>? {
        val ip = prefs.getString(lastServerIpKey, null) ?: return null
        val port = prefs.getInt(lastServerPortKey, defaultPort)
        return ip to port
    }

    fun buildServerUrl(ip: String, port: Int, pin: String?, forceFresh: Boolean, theme: String): String {
        val params = mutableListOf("theme=$theme")
        if (!pin.isNullOrEmpty()) {
            params.add("pin=${URLEncoder.encode(pin, "UTF-8")}")
        }
        params.add("client=${URLEncoder.encode(getClientId(), "UTF-8")}")
        if (forceFresh) {
            params.add("nocache=${System.currentTimeMillis()}")
        }
        return "http://$ip:$port?${params.joinToString("&")}" 
    }

    suspend fun pingWithRetry(
        ip: String,
        port: Int,
        pin: String?,
        maxRetry: Int,
        baseRetryMs: Long
    ): PingResult {
        var last = PingResult(false, false, false, false, 0, null)
        repeat(maxRetry) { attempt ->
            last = pingServer(ip, port, pin)
            if ((last.ok && last.ready) || (last.authRequired && !last.authorized)) {
                return last
            }

            val backoff = baseRetryMs * (1L shl attempt).coerceAtMost(8000L / baseRetryMs)
            delay(backoff)
        }
        return last
    }

    suspend fun pingServer(ip: String, port: Int, pin: String?): PingResult = withContext(Dispatchers.IO) {
        try {
            val clientId = getClientId()
            val url = URL("http://$ip:$port/api/ping?client=${URLEncoder.encode(clientId, "UTF-8")}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                if (!pin.isNullOrEmpty()) {
                    setRequestProperty("X-Lanflix-Pin", pin)
                }
                setRequestProperty("X-Lanflix-Client", clientId)
            }

            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
            } catch (_: Exception) {
                null
            } ?: ""

            val json = runCatching { JSONObject(body) }.getOrNull()
            val authRequired = json?.optBoolean("authRequired", false) ?: false
            val authorized = json?.optBoolean("authorized", !authRequired) ?: (!authRequired)
            val ready = json?.optBoolean("ready", code == 200) ?: (code == 200)
            val ok = code == 200 || code == 401 || code == 503

            PingResult(
                ok = ok,
                ready = ready,
                authRequired = authRequired,
                authorized = authorized,
                statusCode = code,
                errorMessage = if (ok) null else json?.optString("error")
            )
        } catch (e: Exception) {
            PingResult(false, false, false, false, 0, e.message)
        }
    }

    fun sendDisconnectSignal(host: String, port: Int, pin: String?) {
        val clientId = getClientId()
        thread(start = true) {
            try {
                val url = URL("http://$host:$port/api/bye?client=${URLEncoder.encode(clientId, "UTF-8")}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2000
                    readTimeout = 2000
                    setRequestProperty("X-Lanflix-Client", clientId)
                    if (!pin.isNullOrEmpty()) {
                        setRequestProperty("X-Lanflix-Pin", pin)
                    }
                }
                runCatching { conn.inputStream?.close() }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
