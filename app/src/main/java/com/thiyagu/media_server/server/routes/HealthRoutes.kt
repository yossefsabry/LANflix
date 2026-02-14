package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Routing.registerHealthRoutes(server: KtorMediaStreamingServer) {
    get("/api/ping") {
        val authRequired = server.authManager.isAuthEnabled()
        val authorized = server.authManager.isAuthorized(call)
        val ready = server.isServerReady()

        val status = when {
            authRequired && !authorized -> HttpStatusCode.Unauthorized
            !ready -> HttpStatusCode.ServiceUnavailable
            else -> HttpStatusCode.OK
        }
        call.noStore()
        val json = """{"ok": ${ready && status == HttpStatusCode.OK}, "ready": $ready, "authRequired": $authRequired, "authorized": $authorized, "serverTime": ${System.currentTimeMillis()}}"""
        call.respondText(json, ContentType.Application.Json, status)
    }

    route("/api/bye") {
        post {
            if (!call.requireAuth(server)) return@post
            call.noStore()
            val clientKey = server.getClientKey(call)
            server.clientStatsTracker.markDisconnected(clientKey)
            call.respondText("""{"ok": true}""", ContentType.Application.Json, HttpStatusCode.OK)
        }
        get {
            if (!call.requireAuth(server)) return@get
            call.noStore()
            val clientKey = server.getClientKey(call)
            server.clientStatsTracker.markDisconnected(clientKey)
            call.respondText("""{"ok": true}""", ContentType.Application.Json, HttpStatusCode.OK)
        }
    }

    get("/api/ready") {
        if (!call.requireAuth(server)) return@get
        val status = server.scanStatus.value
        call.noStore()
        val json = """{"ready": ${server.isServerReady()}, "scanning": ${status.isScanning}, "count": ${status.count}}"""
        call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
    }
}
