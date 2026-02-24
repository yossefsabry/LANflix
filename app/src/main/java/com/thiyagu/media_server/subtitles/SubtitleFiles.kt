package com.thiyagu.media_server.subtitles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

internal fun subtitleDir(
    context: Context,
    videoKey: String
): File {
    val baseDir = File(context.filesDir, BASE_DIR)
    val safeKey = hashVideoKey(videoKey)
    return File(baseDir, safeKey)
}

internal fun findLocalSubtitle(
    context: Context,
    videoKey: String
): File? {
    val dir = subtitleDir(context, videoKey)
    if (!dir.exists()) return null
    val files = dir.listFiles() ?: return null
    return files.firstOrNull { file ->
        val ext = file.extension.lowercase()
        ext in SUBTITLE_EXTS
    }
}

internal fun listLocalSubtitles(
    context: Context,
    videoKey: String
): List<File> {
    val dir = subtitleDir(context, videoKey)
    if (!dir.exists()) return emptyList()
    val files = dir.listFiles() ?: return emptyList()
    return files.filter { file ->
        val ext = file.extension.lowercase()
        ext in SUBTITLE_EXTS
    }.sortedBy { file ->
        file.name.lowercase()
    }
}

internal fun queryDisplayName(
    context: Context,
    uri: Uri
): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(
        uri,
        projection,
        null,
        null,
        null
    )?.use { cursor ->
        val index = cursor.getColumnIndex(
            OpenableColumns.DISPLAY_NAME
        )
        if (index == -1) {
            return@use null
        }
        if (!cursor.moveToFirst()) {
            return@use null
        }
        cursor.getString(index)
    }
}

internal fun sanitizeSubtitleFileName(
    name: String,
    fallback: String
): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        return fallback
    }
    return trimmed.replace("/", "_")
        .replace("\\", "_")
}

private fun hashVideoKey(videoKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(videoKey.toByteArray())
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val hex = (byte.toInt() and 0xff) + 0x100
        val piece = hex.toString(16).substring(1)
        builder.append(piece)
    }
    return builder.toString()
}

private const val BASE_DIR = "subtitles"

private val SUBTITLE_EXTS =
    setOf("srt")
