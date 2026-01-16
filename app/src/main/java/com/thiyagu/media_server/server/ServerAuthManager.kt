package com.thiyagu.media_server.server

import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.utils.PinAuth
import io.ktor.server.application.*
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

class ServerAuthManager(
    private val userPreferences: UserPreferences,
    private val scope: CoroutineScope
) {
    private val _config = MutableStateFlow(AuthConfig(false, null, null))
    private val config: StateFlow<AuthConfig> = _config.asStateFlow()

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
        return cfg.enabled && !cfg.pinHash.isNullOrBlank() && !cfg.pinSalt.isNullOrBlank()
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

    fun extractPin(call: ApplicationCall): String? {
        return call.request.headers["X-Lanflix-Pin"] ?: call.request.queryParameters["pin"]
    }

    fun getStatus(): AuthStatus {
        return AuthStatus(enabled = isAuthEnabled())
    }
}
