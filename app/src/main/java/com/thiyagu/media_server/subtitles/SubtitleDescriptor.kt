package com.thiyagu.media_server.subtitles

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java.io.File
import java.util.Locale

internal data class SubtitleDescriptor(
    val file: File,
    val language: String?,
    val label: String,
    val trackId: String
)

internal fun buildSubtitleDescriptor(
    file: File
): SubtitleDescriptor {
    val base = file.nameWithoutExtension
    val tokens = splitTokens(base)
    val code = detectLanguage(tokens)
    val label = if (code == null) {
        if (base.isNotBlank()) base else file.name
    } else {
        languageLabel(code)
    }
    return SubtitleDescriptor(
        file = file,
        language = code,
        label = label,
        trackId = file.absolutePath
    )
}

internal fun buildSubtitleConfiguration(
    descriptor: SubtitleDescriptor
): MediaItem.SubtitleConfiguration {
    return MediaItem.SubtitleConfiguration
        .Builder(Uri.fromFile(descriptor.file))
        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
        .setLanguage(descriptor.language)
        .setLabel(descriptor.label)
        .setId(descriptor.trackId)
        .build()
}

private fun splitTokens(value: String): List<String> {
    return value.lowercase(Locale.getDefault())
        .split(
            '.', '_', '-', ' ', '[', ']',
            '(', ')'
        )
        .filter { token -> token.isNotBlank() }
}

private fun detectLanguage(tokens: List<String>): String? {
    if (tokens.any { token ->
            token == "ar" ||
                token == "ara" ||
                token == "arabic"
        }
    ) {
        return "ar"
    }
    if (tokens.any { token ->
            token == "en" ||
                token == "eng" ||
                token == "english"
        }
    ) {
        return "en"
    }
    return null
}

private fun languageLabel(code: String): String {
    val locale = Locale(code)
    val label = locale.getDisplayLanguage(Locale.getDefault())
    return if (label.isBlank()) code else label
}
