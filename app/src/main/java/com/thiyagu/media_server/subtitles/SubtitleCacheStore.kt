package com.thiyagu.media_server.subtitles

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

class SubtitleCacheStore(private val context: Context) {

    private val gson = Gson()
    private val lock = Any()

    private val cacheFile: File
        get() = File(context.cacheDir, CACHE_FILE_NAME)

    private var entries:
        MutableMap<String, SubtitleCacheEntry> =
        loadFromDisk().toMutableMap()

    fun get(videoKey: String): SubtitleCacheEntry? {
        return synchronized(lock) { entries[videoKey] }
    }

    fun put(videoKey: String, entry: SubtitleCacheEntry) {
        val payload = synchronized(lock) {
            entries[videoKey] = entry
            CachePayload(entries = entries.toMap())
        }
        saveToDisk(payload)
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
        runCatching {
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        }
    }

    private fun loadFromDisk(): Map<String, SubtitleCacheEntry> {
        val file = cacheFile
        if (!file.exists()) {
            return emptyMap()
        }
        return try {
            val json = file.readText()
            val payload = gson.fromJson(
                json,
                CachePayload::class.java
            )
            payload?.entries ?: emptyMap()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    private fun saveToDisk(payload: CachePayload) {
        try {
            val parent = cacheFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val json = gson.toJson(payload)
            val tempFile = File(
                parent ?: context.cacheDir,
                cacheFile.name + ".tmp"
            )
            tempFile.writeText(json)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            tempFile.renameTo(cacheFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private data class CachePayload(
        @SerializedName("version")
        val version: Int = CACHE_VERSION,
        @SerializedName("entries")
        val entries:
            Map<String, SubtitleCacheEntry>
    )

    private companion object {
        private const val CACHE_VERSION = 1
        private const val CACHE_FILE_NAME =
            "lanflix_subtitle_cache.json"
    }
}
