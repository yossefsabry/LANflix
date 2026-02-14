package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

internal fun Routing.registerDiagnosticsRoutes(server: KtorMediaStreamingServer) {
    get("/api/connections") {
        if (!call.requireAuth(server)) return@get
        call.noStore()
        val stats = server.getConnectionStats()
        val json = """{"connectedDevices": ${stats.connectedDevices}, "streamingDevices": ${stats.streamingDevices}, "activeStreams": ${stats.activeStreams}}"""
        call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
    }
}
