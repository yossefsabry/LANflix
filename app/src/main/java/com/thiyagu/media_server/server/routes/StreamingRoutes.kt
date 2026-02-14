package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import com.thiyagu.media_server.utils.ThumbnailUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.FileInputStream

internal fun Routing.registerStreamingRoutes(server: KtorMediaStreamingServer) {
    get("/api/thumbnail/{filename}") {
        if (!call.requireAuth(server)) return@get
        val filename = call.parameters["filename"]
        val path = call.request.queryParameters["path"]

        if (filename != null) {
            var targetUri: android.net.Uri? = null
            var length = 0L
            var lastModified = 0L

            var entry = server.getVideoEntry(path, filename)
            if (entry == null) {
                server.refreshVideoIndexFromCacheIfStale()
                entry = server.getVideoEntry(path, filename)
            }

            val documentId = entry?.documentId
            if (!documentId.isNullOrEmpty()) {
                targetUri = server.buildDocumentUri(documentId)
                length = entry?.size ?: length
                lastModified = entry?.lastModified ?: lastModified
            }

            if (targetUri == null && !path.isNullOrEmpty()) {
                val resolved = server.resolveDocumentFileByPath(path)
                if (resolved != null) {
                    server.updateEntryFromDocumentFile(path, resolved)
                    targetUri = resolved.uri
                    length = resolved.length()
                    lastModified = resolved.lastModified()
                }
            }

            if (targetUri == null) {
                val fallback = server.cachedFilesMap[filename]
                if (fallback != null) {
                    server.updateEntryFromDocumentFile(path, fallback)
                    targetUri = fallback.uri
                    length = fallback.length()
                    lastModified = fallback.lastModified()
                }
            }

            val resolvedUri = targetUri ?: run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            val etagValue = "\"${length}-${lastModified}\""
            val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
            if (ifNoneMatch == etagValue) {
                call.respond(HttpStatusCode.NotModified)
                return@get
            }

            val memCacheKey = if (path != null) "path:$path" else "file:$filename"
            val cachedBytes = ThumbnailUtils.ThumbnailMemoryCache.get(memCacheKey)
            if (cachedBytes != null) {
                call.response.headers.append(HttpHeaders.ETag, etagValue)
                call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=31536000")
                call.respondBytes(cachedBytes, ContentType.Image.Any)
                return@get
            }

            val thumbnail = ThumbnailUtils.generateThumbnail(server.appContext, resolvedUri, filename)
            if (thumbnail != null) {
                ThumbnailUtils.ThumbnailMemoryCache.put(memCacheKey, thumbnail)
                call.response.headers.append(HttpHeaders.ETag, etagValue)
                call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=31536000")
                call.respondBytes(thumbnail, ContentType.parse("image/webp"))
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    get("/{filename}") {
        if (!call.requireAuth(server)) return@get
        val filename = call.parameters["filename"]
        val path = call.request.queryParameters["path"]

        if (filename != null) {
            var entry = server.getVideoEntry(path, filename)
            if (entry == null) {
                server.refreshVideoIndexFromCacheIfStale()
                entry = server.getVideoEntry(path, filename)
            }

            var targetUri: android.net.Uri? = null
            var length = entry?.size ?: 0L

            val documentId = entry?.documentId
            if (!documentId.isNullOrEmpty()) {
                targetUri = server.buildDocumentUri(documentId)
            }

            if (targetUri == null && !path.isNullOrEmpty()) {
                val resolved = server.resolveDocumentFileByPath(path)
                if (resolved != null) {
                    server.updateEntryFromDocumentFile(path, resolved)
                    targetUri = resolved.uri
                    length = resolved.length()
                }
            }

            if (targetUri == null) {
                val fallback = server.cachedFilesMap[filename]
                if (fallback != null) {
                    server.updateEntryFromDocumentFile(path, fallback)
                    targetUri = fallback.uri
                    length = fallback.length()
                }
            }

            val resolvedUri = targetUri ?: run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            val contentResolver = server.appContext.contentResolver
            var pfd: android.os.ParcelFileDescriptor? = try {
                contentResolver.openFileDescriptor(resolvedUri, "r")
            } catch (_: Exception) {
                null
            }

            if (pfd == null && !path.isNullOrEmpty()) {
                server.refreshVideoIndexFromCacheIfStale()
                entry = server.getVideoEntry(path, filename)
                val refreshedDocumentId = entry?.documentId
                if (!refreshedDocumentId.isNullOrEmpty()) {
                    val refreshedUri = server.buildDocumentUri(refreshedDocumentId)
                    length = entry?.size ?: length
                    pfd = try {
                        contentResolver.openFileDescriptor(refreshedUri, "r")
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            if (pfd == null && !path.isNullOrEmpty()) {
                val resolved = server.resolveDocumentFileByPath(path)
                if (resolved != null) {
                    server.updateEntryFromDocumentFile(path, resolved)
                    val resolvedDocumentUri = resolved.uri
                    length = resolved.length()
                    pfd = try {
                        contentResolver.openFileDescriptor(resolvedDocumentUri, "r")
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            if (pfd == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            val statSize = if (pfd.statSize > 0) pfd.statSize else -1L
            val queriedSize = if (statSize > 0) -1L else queryContentLength(contentResolver, resolvedUri)
            val totalLength = when {
                statSize > 0 -> statSize
                queriedSize > 0 -> queriedSize
                length > 0 -> length
                else -> 0L
            }
            val rangeHeader = call.request.headers[HttpHeaders.Range]
            val supportsRanges = totalLength > 0
            val range = if (supportsRanges) parseRangeHeader(rangeHeader, totalLength) else null

            if (supportsRanges && rangeHeader != null && range == null) {
                call.response.headers.append(HttpHeaders.ContentRange, "bytes */$totalLength")
                pfd.close()
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                return@get
            }

            if (supportsRanges) {
                call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
            }

            if (range != null) {
                call.response.headers.append(
                    HttpHeaders.ContentRange,
                    "bytes ${range.first}-${range.last}/$totalLength"
                )
            }

            val responseLength = if (range != null) (range.last - range.first + 1) else totalLength
            val contentType = contentTypeForFile(filename)

            call.respond(object : OutgoingContent.ReadChannelContent() {
                override val contentLength: Long? = if (responseLength > 0) responseLength else null
                override val contentType = contentType
                override val status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK

                override fun readFrom(): ByteReadChannel {
                    val clientKey = server.getClientKey(call)
                    server.clientStatsTracker.startStreaming(clientKey)

                    val inputStream = FileInputStream(pfd.fileDescriptor)
                    val channel = inputStream.channel
                    if (range != null) {
                        channel.position(range.first)
                    }

                    val trackingStream = KtorMediaStreamingServer.TrackingInputStream(inputStream) {
                        server.clientStatsTracker.stopStreaming(clientKey)
                        try { pfd.close() } catch (_: Exception) { }
                    }

                    val bounded = if (range != null) {
                        KtorMediaStreamingServer.BoundedInputStream(trackingStream, responseLength)
                    } else {
                        trackingStream
                    }

                    return bounded.toByteReadChannel()
                }
            })
        }
    }

    get("/api/exists/{filename}") {
        if (!call.requireAuth(server)) return@get
        call.noStore()
        val filename = call.parameters["filename"]
        val path = call.request.queryParameters["path"]

        if (filename != null) {
            var entry = server.getVideoEntry(path, filename)
            if (entry == null) {
                server.refreshVideoIndexFromCacheIfStale()
                entry = server.getVideoEntry(path, filename)
            }

            var targetUri: android.net.Uri? = null
            var length = entry?.size ?: 0L

            val documentId = entry?.documentId
            if (!documentId.isNullOrEmpty()) {
                targetUri = server.buildDocumentUri(documentId)
            }

            val resolvedUri = targetUri
            if (resolvedUri != null) {
                val pfd = try {
                    server.appContext.contentResolver.openFileDescriptor(resolvedUri, "r")
                } catch (_: Exception) {
                    null
                }
                if (pfd != null) {
                    val statSize = if (pfd.statSize > 0) pfd.statSize else length
                    pfd.close()
                    call.respondText(
                        """{"exists": true, "size": ${if (statSize > 0) statSize else length}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                    return@get
                }
            }

            if (!path.isNullOrEmpty()) {
                val resolved = server.resolveDocumentFileByPath(path)
                if (resolved != null && resolved.exists()) {
                    server.updateEntryFromDocumentFile(path, resolved)
                    call.respondText(
                        """{"exists": true, "size": ${resolved.length()}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                    return@get
                }
            }

            val fallback = server.cachedFilesMap[filename]
            if (fallback != null && fallback.exists()) {
                server.updateEntryFromDocumentFile(path, fallback)
                call.respondText(
                    """{"exists": true, "size": ${fallback.length()}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
            } else {
                call.respondText("""{"exists": false}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
        } else {
            call.respond(HttpStatusCode.BadRequest)
        }
    }
}

private fun queryContentLength(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri
): Long {
    val projection = arrayOf(
        android.provider.DocumentsContract.Document.COLUMN_SIZE,
        android.provider.OpenableColumns.SIZE
    )
    return try {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use -1L
            val documentIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)
            val openableIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            val size = when {
                documentIndex >= 0 && !cursor.isNull(documentIndex) -> cursor.getLong(documentIndex)
                openableIndex >= 0 && !cursor.isNull(openableIndex) -> cursor.getLong(openableIndex)
                else -> -1L
            }
            if (size > 0) size else -1L
        } ?: -1L
    } catch (_: Exception) {
        -1L
    }
}
