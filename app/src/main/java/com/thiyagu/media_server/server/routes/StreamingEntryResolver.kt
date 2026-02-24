package com.thiyagu.media_server.server.routes

import android.net.Uri
import com.thiyagu.media_server.server.KtorMediaStreamingServer

internal data class ResolvedVideo(
    val uri: Uri,
    val length: Long,
    val lastModified: Long
)

internal fun resolveVideoEntry(
    server: KtorMediaStreamingServer,
    filename: String,
    path: String?
): KtorMediaStreamingServer.CachedVideoEntry? {
    var entry = server.getVideoEntry(path, filename)
    if (entry == null) {
        server.refreshVideoIndexFromCacheIfStale()
        entry = server.getVideoEntry(path, filename)
    }
    return entry
}

internal fun resolveVideo(
    server: KtorMediaStreamingServer,
    filename: String,
    path: String?
): ResolvedVideo? {
    val entry = resolveVideoEntry(server, filename, path)
    var targetUri: Uri? = null
    var length = entry?.size ?: 0L
    var lastModified = entry?.lastModified ?: 0L

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

    val resolvedUri = targetUri ?: return null
    return ResolvedVideo(
        uri = resolvedUri,
        length = length,
        lastModified = lastModified
    )
}
