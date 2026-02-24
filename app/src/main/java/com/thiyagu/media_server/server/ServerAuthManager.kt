package com.thiyagu.media_server.server

import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.utils.PinAuth
import io.ktor.server.application.ApplicationCall
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AuthStatus(
    val enabled: Boolean
)

private data class AuthConfig(
    val enabled: Boolean,
    val pinHash: String?,
    val pinSalt: String?
)

data class CastToken(
    val token: String,
    val expiresAtMs: Long
)

private data class CastTokenEntry(
    val token: String,
    val filename: String,
    val path: String?,
    val clientId: String,
    val expiresAtMs: Long
)

class ServerAuthManager(
    private val userPreferences: UserPreferences,
    private val scope: CoroutineScope
) {
    private val _config =
        MutableStateFlow(AuthConfig(false, null, null))
    private val config: StateFlow<AuthConfig> = _config.asStateFlow()
    private val castTokens =
        ConcurrentHashMap<String, CastTokenEntry>()

    init {
        scope.launch {
            combine(
                userPreferences.serverAuthEnabledFlow,
                userPreferences.serverAuthPinHashFlow,
                userPreferences.serverAuthPinSaltFlow
            ) { enabled, hash, salt ->
                AuthConfig(enabled, hash, salt)
            }.collect { _config.value = it }
        }
    }

    fun isAuthEnabled(): Boolean {
        val cfg = _config.value
        return cfg.enabled &&
            !cfg.pinHash.isNullOrBlank() &&
            !cfg.pinSalt.isNullOrBlank()
    }

    fun isAuthorized(pin: String?): Boolean {
        if (!isAuthEnabled()) return true
        if (pin.isNullOrBlank()) return false
        val cfg = _config.value
        val salt = cfg.pinSalt ?: return false
        return PinAuth.hashPin(pin, salt) == cfg.pinHash
    }

    fun isAuthorized(call: ApplicationCall): Boolean {
        return isAuthorized(extractPin(call))
    }

    fun isCastTokenAuthorized(
        call: ApplicationCall,
        filename: String,
        path: String?
    ): Boolean {
        if (!isAuthEnabled()) return true
        val token =
            call.request.queryParameters["token"] ?: return false
        val client = extractClientId(call)
        return isCastTokenValid(token, filename, path, client)
    }

    fun issueCastToken(
        filename: String,
        path: String?,
        clientId: String
    ): CastToken {
        val now = System.currentTimeMillis()
        pruneExpiredTokens(now)
        val token = UUID.randomUUID().toString()
        val expiresAtMs = now + CAST_TOKEN_TTL_MS
        val entry = CastTokenEntry(
            token = token,
            filename = filename,
            path = path,
            clientId = clientId,
            expiresAtMs = expiresAtMs
        )
        castTokens[token] = entry
        return CastToken(token = token, expiresAtMs = expiresAtMs)
    }

    fun extractPin(call: ApplicationCall): String? {
        return call.request.headers["X-Lanflix-Pin"]
            ?: call.request.queryParameters["pin"]
    }

    fun getStatus(): AuthStatus {
        return AuthStatus(enabled = isAuthEnabled())
    }

    private fun extractClientId(call: ApplicationCall): String {
        val headerId = call.request.headers["X-Lanflix-Client"]
        val queryId = call.request.queryParameters["client"]
        return (headerId ?: queryId).orEmpty().trim()
    }

    private fun isCastTokenValid(
        token: String,
        filename: String,
        path: String?,
        clientId: String
    ): Boolean {
        val now = System.currentTimeMillis()
        val entry = castTokens[token] ?: return false
        if (entry.expiresAtMs <= now) {
            castTokens.remove(token)
            return false
        }
        if (entry.filename != filename) return false
        if ((entry.path ?: "") != (path ?: "")) return false
        if (entry.clientId != clientId) return false
        return true
    }

    private fun pruneExpiredTokens(now: Long) {
        val iterator = castTokens.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.expiresAtMs <= now) {
                iterator.remove()
            }
        }
    }

    private companion object {
        private const val CAST_TOKEN_TTL_MS = 7 * 60 * 1000L
    }
}
