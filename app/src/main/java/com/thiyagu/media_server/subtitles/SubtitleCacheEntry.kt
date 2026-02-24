package com.thiyagu.media_server.subtitles

data class SubtitleCacheEntry(
    val status: SubtitleStatus,
    val localPath: String?,
    val provider: String,
    val checkedAt: Long,
    val lang: String?
) {
    fun isExpired(ttlMs: Long): Boolean {
        if (status != SubtitleStatus.UNAVAILABLE) {
            return false
        }
        val ageMs = System.currentTimeMillis() - checkedAt
        return ageMs >= ttlMs
    }
}
