package com.thiyagu.media_server.subtitles.provider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.thiyagu.media_server.subtitles.SubtitleFetchResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream

class OpenSubtitlesProvider(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val baseUrl: String = SEARCH_BASE_URL
) : SubtitleProvider {
    private val gson = Gson()
    override suspend fun fetchSubtitle(
        title: String,
        lang: String,
        destDir: File
    ): SubtitleFetchResult {
        val query = title.trim()
        if (query.isEmpty()) {
            return SubtitleFetchResult.Unavailable
        }
        val langCode = toLangCode(lang)
        val results = searchResults(query, langCode)
            ?: return SubtitleFetchResult.Error
        val result = results.firstOrNull {
            !it.downloadLink.isNullOrEmpty()
        } ?: return SubtitleFetchResult.Unavailable
        val link = result.downloadLink
            ?: return SubtitleFetchResult.Unavailable
        val name = result.fileName ?: DEFAULT_FILE_NAME
        val file = download(link, name, destDir)
            ?: return SubtitleFetchResult.Error
        return SubtitleFetchResult.Available(file)
    }
    private fun searchResults(
        query: String,
        lang: String
    ): List<SearchResult>? {
        val url = buildSearchUrl(query, lang)
        val json = fetchJson(url) ?: return null
        return runCatching {
            gson.fromJson(
                json,
                Array<SearchResult>::class.java
            ).toList()
        }.getOrNull()
    }
    private fun buildSearchUrl(
        query: String,
        lang: String
    ): String {
        val encoded = URLEncoder.encode(
            query,
            "UTF-8"
        ).replace("+", "%20")
        return baseUrl +
            "/query-" + encoded +
            "/sublanguageid-" + lang
    }
    private fun fetchJson(url: String): String? {
        val connection =
            URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", userAgent)
        return try {
            if (connection.responseCode != 200) {
                null
            } else {
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection.disconnect()
        }
    }
    private fun download(
        link: String,
        name: String,
        destDir: File
    ): File? {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val safeName = sanitizeFileName(name)
        val lowerName = safeName.lowercase()
        if (!lowerName.endsWith(".srt") &&
            !lowerName.endsWith(".srt.gz")
        ) {
            return null
        }
        val baseName = stripGzipSuffix(safeName)
        val finalName = ensureSubtitleExt(baseName)
        val target = File(destDir, finalName)
        val connection =
            URL(link).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", userAgent)
        return try {
            val input = if (shouldGunzip(connection, link)) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            input.use { stream ->
                target.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }
            if (target.exists() && target.length() > 0L) {
                target
            } else {
                target.delete()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            target.delete()
            null
        } finally {
            connection.disconnect()
        }
    }
    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace("/", "_")
            .replace("\\", "_")
            .trim()
        return if (cleaned.isBlank()) {
            DEFAULT_FILE_NAME
        } else {
            cleaned
        }
    }
    private fun stripGzipSuffix(name: String): String {
        return if (name.endsWith(".gz")) {
            name.dropLast(3)
        } else {
            name
        }
    }
    private fun ensureSubtitleExt(name: String): String {
        val ext = name.substringAfterLast('.', "")
            .lowercase()
        return if (ext in SUBTITLE_EXTS) {
            name
        } else {
            name + ".srt"
        }
    }
    private fun shouldGunzip(
        connection: HttpURLConnection,
        link: String
    ): Boolean {
        if (link.endsWith(".gz")) {
            return true
        }
        val encoding = connection.contentEncoding ?: return false
        return encoding.lowercase().contains("gzip")
    }
    private fun toLangCode(lang: String): String {
        val trimmed = lang.trim()
        if (trimmed.isEmpty()) {
            return DEFAULT_LANG
        }
        return try {
            Locale(trimmed).isO3Language
                .lowercase()
        } catch (_: Exception) {
            DEFAULT_LANG
        }
    }
    private data class SearchResult(
        @SerializedName("SubDownloadLink")
        val downloadLink: String?,
        @SerializedName("SubFileName")
        val fileName: String?
    )
    private companion object {
        private const val DEFAULT_LANG = "eng"
        private const val DEFAULT_FILE_NAME = "subtitle.srt"
        private const val DEFAULT_USER_AGENT = "LANflix"
        private const val SEARCH_BASE_URL =
            "https://rest.opensubtitles.org/search"
        private const val TIMEOUT_MS = 10_000
        private val SUBTITLE_EXTS = setOf("srt")
    }
}
