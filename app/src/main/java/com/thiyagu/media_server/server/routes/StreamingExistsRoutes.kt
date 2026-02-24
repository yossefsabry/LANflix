package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

internal fun Routing.registerExistsRoutes(
    server: KtorMediaStreamingServer
) {
    get("/api/exists/{filename}") {
        if (!call.requireAuth(server)) return@get
        call.noStore()
        val filename = call.parameters["filename"]
        val path = call.request.queryParameters["path"]
        if (filename.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }

        val resolved = resolveVideo(server, filename, path)
        if (resolved != null) {
            val pfd = try {
                server.appContext.contentResolver
                    .openFileDescriptor(resolved.uri, "r")
            } catch (_: Exception) {
                null
            }
            if (pfd != null) {
                val statSize =
                    if (pfd.statSize > 0) pfd.statSize
                    else resolved.length
                pfd.close()
                call.respondText(
                    """{"exists": true, "size": $statSize}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
                return@get
            }
        }

        if (!path.isNullOrEmpty()) {
            val resolvedFile = server.resolveDocumentFileByPath(path)
            if (resolvedFile != null && resolvedFile.exists()) {
                server.updateEntryFromDocumentFile(path, resolvedFile)
                val size = resolvedFile.length()
                call.respondText(
                    """{"exists": true, "size": $size}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
                return@get
            }
        }

        val fallback = server.cachedFilesMap[filename]
        if (fallback != null && fallback.exists()) {
            server.updateEntryFromDocumentFile(path, fallback)
            val size = fallback.length()
            call.respondText(
                """{"exists": true, "size": $size}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        } else {
            call.respondText(
                """{"exists": false}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound
            )
        }
    }
}
