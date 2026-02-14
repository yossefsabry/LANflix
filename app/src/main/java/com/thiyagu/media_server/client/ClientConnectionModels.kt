package com.thiyagu.media_server.client

internal data class PingResult(
    val ok: Boolean,
    val ready: Boolean,
    val authRequired: Boolean,
    val authorized: Boolean,
    val statusCode: Int,
    val errorMessage: String? = null
)

internal enum class ConnectionPhase {
    IDLE,
    PINGING,
    AUTH_REQUIRED,
    LOADING,
    CONNECTED,
    ERROR
}

internal enum class ConnectReason {
    USER,
    AUTO,
    RETRY
}
