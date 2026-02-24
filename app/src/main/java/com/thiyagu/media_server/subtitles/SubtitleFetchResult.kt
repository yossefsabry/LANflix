package com.thiyagu.media_server.subtitles

import java.io.File

sealed class SubtitleFetchResult {
    data class Available(val file: File) : SubtitleFetchResult()

    object Unavailable : SubtitleFetchResult()

    object Error : SubtitleFetchResult()
}
