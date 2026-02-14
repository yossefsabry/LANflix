package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

internal fun parseRangeHeader(rangeHeader: String?, totalLength: Long): LongRange? {
    if (rangeHeader.isNullOrBlank()) return null
    if (!rangeHeader.startsWith("bytes=")) return null
    if (totalLength <= 0) return null

    val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
    val firstRange = rangeSpec.split(',').firstOrNull()?.trim() ?: return null
    val parts = firstRange.split('-', limit = 2)
    if (parts.size != 2) return null

    val startPart = parts[0].trim()
    val endPart = parts[1].trim()

    return if (startPart.isEmpty()) {
        val suffixLength = endPart.toLongOrNull() ?: return null
        if (suffixLength <= 0) return null
        val start = (totalLength - suffixLength).coerceAtLeast(0)
        val end = totalLength - 1
        if (start > end) null else start..end
    } else {
        val start = startPart.toLongOrNull() ?: return null
        if (start < 0) return null
        val end = if (endPart.isEmpty()) {
            totalLength - 1
        } else {
            endPart.toLongOrNull() ?: return null
        }
        if (start > end) return null
        val boundedEnd = minOf(end, totalLength - 1)
        if (start > boundedEnd) null else start..boundedEnd
    }
}

internal fun contentTypeForFile(filename: String): ContentType {
    return when {
        filename.endsWith(".mp4", true) -> ContentType.Video.MP4
        filename.endsWith(".mkv", true) -> ContentType.parse("video/x-matroska")
        filename.endsWith(".webm", true) -> ContentType.parse("video/webm")
        filename.endsWith(".avi", true) -> ContentType.parse("video/x-msvideo")
        filename.endsWith(".mov", true) -> ContentType.parse("video/quicktime")
        else -> ContentType.Video.Any
    }
}

internal suspend fun ApplicationCall.requireAuth(server: KtorMediaStreamingServer): Boolean {
    if (server.authManager.isAuthorized(this)) return true
    respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
    return false
}

internal fun ApplicationCall.noStore() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
}
