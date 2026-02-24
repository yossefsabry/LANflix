package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

internal fun Routing.registerCastRoutes(
    server: KtorMediaStreamingServer
) {
    get("/api/cast/token") {
        if (!server.authManager.isAuthEnabled()) {
            call.noStore()
            call.respondText(
                """{"error":"pin_disabled"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@get
        }
        if (!call.requireAuth(server)) return@get
        call.noStore()
        val filename = call.request.queryParameters["filename"]
        if (filename.isNullOrEmpty()) {
            call.respondText(
                """{"error":"missing_filename"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@get
        }
        val path = call.request.queryParameters["path"]
        val client =
            call.request.queryParameters["client"]
                ?: call.request.headers["X-Lanflix-Client"]
                ?: ""
        val token = server.authManager.issueCastToken(
            filename = filename,
            path = path,
            clientId = client
        )
        val body =
            """{"token":"${token.token}",""" +
                """"expiresAtMs":${token.expiresAtMs}}"""
        call.respondText(
            body,
            ContentType.Application.Json,
            HttpStatusCode.OK
        )
    }
}
