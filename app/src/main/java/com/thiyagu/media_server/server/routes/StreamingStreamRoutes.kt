package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.FileInputStream

internal fun Routing.registerStreamRoutes(
    server: KtorMediaStreamingServer
) {
    options("/{filename}") {
        call.applyStreamCors()
        call.respond(HttpStatusCode.OK)
    }

    get("/{filename}") {
        val filename = call.parameters["filename"]
        if (filename.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val path = call.request.queryParameters["path"]
        if (!call.requireStreamAuth(server, filename, path)) {
            return@get
        }
        call.applyStreamCors()

        val resolved = resolveVideo(server, filename, path)
        if (resolved == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val contentResolver = server.appContext.contentResolver
        var targetUri = resolved.uri
        var length = resolved.length
        var pfd = openFileDescriptor(contentResolver, targetUri)
        if (pfd == null && !path.isNullOrEmpty()) {
            server.refreshVideoIndexFromCacheIfStale()
            val entry = resolveVideoEntry(server, filename, path)
            val documentId = entry?.documentId
            if (!documentId.isNullOrEmpty()) {
                targetUri = server.buildDocumentUri(documentId)
                length = entry?.size ?: length
                pfd = openFileDescriptor(contentResolver, targetUri)
            }
        }

        if (pfd == null && !path.isNullOrEmpty()) {
            val resolvedFile = server.resolveDocumentFileByPath(path)
            if (resolvedFile != null) {
                server.updateEntryFromDocumentFile(path, resolvedFile)
                targetUri = resolvedFile.uri
                length = resolvedFile.length()
                pfd = openFileDescriptor(contentResolver, targetUri)
            }
        }

        if (pfd == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val statSize = if (pfd.statSize > 0) pfd.statSize else -1L
        val queriedSize = if (statSize > 0) {
            -1L
        } else {
            queryContentLength(contentResolver, targetUri)
        }
        val totalLength = when {
            statSize > 0 -> statSize
            queriedSize > 0 -> queriedSize
            length > 0 -> length
            else -> 0L
        }

        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val supportsRanges = totalLength > 0
        val range = if (supportsRanges) {
            parseRangeHeader(rangeHeader, totalLength)
        } else {
            null
        }

        if (supportsRanges && rangeHeader != null && range == null) {
            call.response.headers.append(
                HttpHeaders.ContentRange,
                "bytes */$totalLength"
            )
            pfd.close()
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            return@get
        }

        if (supportsRanges) {
            call.response.headers.append(
                HttpHeaders.AcceptRanges,
                "bytes"
            )
        }

        if (range != null) {
            call.response.headers.append(
                HttpHeaders.ContentRange,
                "bytes ${range.first}-${range.last}/$totalLength"
            )
        }

        val responseLength = if (range != null) {
            range.last - range.first + 1
        } else {
            totalLength
        }
        val contentType = contentTypeForFile(filename)

        call.respond(object : OutgoingContent.ReadChannelContent() {
            override val contentLength: Long? =
                if (responseLength > 0) responseLength else null
            override val contentType = contentType
            override val status = if (range != null) {
                HttpStatusCode.PartialContent
            } else {
                HttpStatusCode.OK
            }

            override fun readFrom(): ByteReadChannel {
                val clientKey = server.getClientKey(call)
                server.clientStatsTracker.startStreaming(clientKey)
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val channel = inputStream.channel
                if (range != null) {
                    channel.position(range.first)
                }
                val tracking =
                    KtorMediaStreamingServer.TrackingInputStream(
                        inputStream
                    ) {
                        server.clientStatsTracker.stopStreaming(
                            clientKey
                        )
                        try {
                            pfd.close()
                        } catch (_: Exception) {
                        }
                    }
                val bounded = if (range != null) {
                    KtorMediaStreamingServer.BoundedInputStream(
                        tracking,
                        responseLength
                    )
                } else {
                    tracking
                }
                return bounded.toByteReadChannel()
            }
        })
    }
}
