package com.thiyagu.media_server.subtitles.provider

import com.thiyagu.media_server.subtitles.SubtitleFetchResult
import java.io.File

interface SubtitleProvider {
    suspend fun fetchSubtitle(
        title: String,
        lang: String,
        destDir: File
    ): SubtitleFetchResult
}
