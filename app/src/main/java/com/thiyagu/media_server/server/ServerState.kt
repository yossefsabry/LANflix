package com.thiyagu.media_server.server

sealed class ServerState {
    object Stopped : ServerState()
    object Starting : ServerState()
    data class Running(val url: String) : ServerState()
    data class Error(val message: String) : ServerState()
}
