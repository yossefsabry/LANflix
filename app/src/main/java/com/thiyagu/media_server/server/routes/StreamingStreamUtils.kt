package com.thiyagu.media_server.server.routes

import android.content.ContentResolver
import android.net.Uri

internal fun openFileDescriptor(
    contentResolver: ContentResolver,
    uri: Uri
): android.os.ParcelFileDescriptor? {
    return try {
        contentResolver.openFileDescriptor(uri, "r")
    } catch (_: Exception) {
        null
    }
}

internal fun queryContentLength(
    contentResolver: ContentResolver,
    uri: Uri
): Long {
    val projection = arrayOf(
        android.provider.DocumentsContract.Document.COLUMN_SIZE,
        android.provider.OpenableColumns.SIZE
    )
    return try {
        contentResolver.query(uri, projection, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use -1L
                val docIndex = cursor.getColumnIndex(
                    android.provider.DocumentsContract.Document
                        .COLUMN_SIZE
                )
                val openableIndex = cursor.getColumnIndex(
                    android.provider.OpenableColumns.SIZE
                )
                val size = when {
                    docIndex >= 0 && !cursor.isNull(docIndex) ->
                        cursor.getLong(docIndex)
                    openableIndex >= 0 &&
                        !cursor.isNull(openableIndex) ->
                        cursor.getLong(openableIndex)
                    else -> -1L
                }
                if (size > 0) size else -1L
            } ?: -1L
    } catch (_: Exception) {
        -1L
    }
}
