package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import com.thiyagu.media_server.utils.ThumbnailUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

internal fun Routing.registerThumbnailRoutes(
    server: KtorMediaStreamingServer
) {
    get("/api/thumbnail/{filename}") {
        if (!call.requireAuth(server)) return@get
        val filename = call.parameters["filename"]
        val path = call.request.queryParameters["path"]
        if (filename.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }

        val resolved = resolveVideo(server, filename, path)
        if (resolved == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val etagValue =
            "\"${resolved.length}-${resolved.lastModified}\""
        val ifNoneMatch =
            call.request.headers[HttpHeaders.IfNoneMatch]
        if (ifNoneMatch == etagValue) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }

        val cacheKey = if (path != null) {
            "path:$path"
        } else {
            "file:$filename"
        }
        val cachedBytes =
            ThumbnailUtils.ThumbnailMemoryCache.get(cacheKey)
        if (cachedBytes != null) {
            call.response.headers.append(HttpHeaders.ETag, etagValue)
            call.response.headers.append(
                HttpHeaders.CacheControl,
                "public, max-age=31536000"
            )
            call.respondBytes(cachedBytes, ContentType.Image.Any)
            return@get
        }

        val thumbnail = ThumbnailUtils.generateThumbnail(
            server.appContext,
            resolved.uri,
            filename
        )
        if (thumbnail != null) {
            ThumbnailUtils.ThumbnailMemoryCache.put(
                cacheKey,
                thumbnail
            )
            call.response.headers.append(HttpHeaders.ETag, etagValue)
            call.response.headers.append(
                HttpHeaders.CacheControl,
                "public, max-age=31536000"
            )
            call.respondBytes(
                thumbnail,
                ContentType.parse("image/webp")
            )
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}
