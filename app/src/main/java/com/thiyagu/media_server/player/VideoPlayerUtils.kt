package com.thiyagu.media_server.player

import android.net.Uri

internal fun sanitizeVideoUrl(url: String): String {
    val uri = Uri.parse(url)
    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames
        .filter { it != "pin" && it != "client" }
        .forEach { key ->
            uri.getQueryParameters(key).forEach { value ->
                builder.appendQueryParameter(key, value)
            }
        }
    return builder.build().toString()
}

internal fun deriveTitle(url: String): String {
    val uri = Uri.parse(url)
    return uri.lastPathSegment?.replace("%20", " ") ?: "Video"
}
