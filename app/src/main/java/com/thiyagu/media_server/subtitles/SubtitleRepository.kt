package com.thiyagu.media_server.subtitles
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import com.thiyagu.media_server.subtitles.provider.SubtitleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class SubtitleRepository(
    private val context: Context,
    private val cacheStore: SubtitleCacheStore,
    private val provider: SubtitleProvider
) {

    suspend fun checkAndCache(
        videoKey: String,
        title: String,
        lang: String
    ) = withContext(Dispatchers.IO) {
        val cached = cacheStore.get(videoKey)
        if (cached != null) {
            if (cached.status == SubtitleStatus.AVAILABLE) {
                val path = cached.localPath
                if (!path.isNullOrEmpty() &&
                    File(path).exists()
                ) {
                    return@withContext
                }
            }
            if (cached.status == SubtitleStatus.UNAVAILABLE &&
                isUnavailableFresh(cached)
            ) {
                return@withContext
            }
        }

        val localFile = findLocalSubtitle(context, videoKey)
        if (localFile != null) {
            cacheStore.put(
                videoKey,
                availableEntry(localFile, PROVIDER_USER, lang)
            )
            return@withContext
        }

        if (!hasInternet()) {
            cacheStore.put(
                videoKey,
                unavailableEntry(lang, PROVIDER_OFFLINE)
            )
            return@withContext
        }

        val destDir = subtitleDir(context, videoKey)
        val result = runCatching {
            provider.fetchSubtitle(title, lang, destDir)
        }.getOrNull()

        when (result) {
            is SubtitleFetchResult.Available -> {
                cacheStore.put(
                    videoKey,
                    availableEntry(result.file, PROVIDER_OPEN, lang)
                )
            }
            SubtitleFetchResult.Unavailable -> {
                cacheStore.put(
                    videoKey,
                    unavailableEntry(lang, PROVIDER_OPEN)
                )
            }
            SubtitleFetchResult.Error, null -> {
                cacheStore.put(
                    videoKey,
                    unavailableEntry(lang, PROVIDER_ERROR)
                )
            }
        }
    }

    suspend fun saveUserSubtitle(
        videoKey: String,
        uri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        val name = queryDisplayName(context, uri)
            ?: DEFAULT_USER_NAME
        val ext = name.substringAfterLast('.', "")
            .lowercase()
        if (ext != "srt") return@withContext false
        val destDir = subtitleDir(context, videoKey)
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val safeName = sanitizeSubtitleFileName(
            name,
            DEFAULT_USER_NAME
        )
        val target = File(destDir, safeName)
        val saved = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            target.exists() && target.length() > 0L
        }.getOrDefault(false)

        if (saved) {
            cacheStore.put(
                videoKey,
                availableEntry(target, PROVIDER_USER, null)
            )
        }
        saved
    }

    fun readCachedStatus(
        videoKey: String
    ): SubtitleCacheEntry? =
        cacheStore.get(videoKey)
    private fun availableEntry(
        file: File,
        provider: String,
        lang: String?
    ): SubtitleCacheEntry {
        return SubtitleCacheEntry(
            status = SubtitleStatus.AVAILABLE,
            localPath = file.absolutePath,
            provider = provider,
            checkedAt = System.currentTimeMillis(),
            lang = lang
        )
    }

    private fun unavailableEntry(
        lang: String?,
        provider: String
    ): SubtitleCacheEntry {
        return SubtitleCacheEntry(
            status = SubtitleStatus.UNAVAILABLE,
            localPath = null,
            provider = provider,
            checkedAt = System.currentTimeMillis(),
            lang = lang
        )
    }

    private fun isUnavailableFresh(
        entry: SubtitleCacheEntry
    ): Boolean {
        val ttlMs = when (entry.provider) {
            PROVIDER_OFFLINE,
            PROVIDER_ERROR -> transientTtlMs
            else -> unavailableTtlMs
        }
        return !entry.isExpired(ttlMs)
    }

    private fun hasInternet(): Boolean {
        val manager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network)
                ?: return false
            return caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        }
        @Suppress("DEPRECATION")
        val info = manager.activeNetworkInfo
        @Suppress("DEPRECATION")
        return info?.isConnected == true
    }

    private val unavailableTtlMs =
        TimeUnit.DAYS.toMillis(UNAVAILABLE_TTL_DAYS.toLong())

    private val transientTtlMs =
        TimeUnit.MINUTES.toMillis(
            TRANSIENT_TTL_MINUTES.toLong()
        )

    private companion object {
        private const val UNAVAILABLE_TTL_DAYS = 7
        private const val TRANSIENT_TTL_MINUTES = 60
        private const val DEFAULT_USER_NAME = "subtitle.srt"
        private const val PROVIDER_USER = "user_upload"
        private const val PROVIDER_OPEN = "opensubtitles"
        private const val PROVIDER_OFFLINE = "offline"
        private const val PROVIDER_ERROR = "error"
    }
}
