package com.thiyagu.media_server.utils

import android.content.Context
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

object ThumbnailUtils {

    // In-Memory LRU Cache
    object ThumbnailMemoryCache {
        private const val MAX_SIZE = 50 * 1024 * 1024 // 50MB
        private val cache = object : LruCache<String, ByteArray>(MAX_SIZE) {
            override fun sizeOf(key: String, value: ByteArray): Int {
                return value.size
            }
        }
        fun get(key: String): ByteArray? = cache.get(key)
        fun put(key: String, value: ByteArray) { cache.put(key, value) }
    }

    fun generateThumbnail(appContext: Context, uri: Uri, filename: String): ByteArray? {
        val cacheDir = File(appContext.cacheDir, "thumbnails")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // Cache Key: MD5 of URI
        val cacheKey = try {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(uri.toString().toByteArray())
            val hexString = StringBuilder()
            for (b in digest.digest()) hexString.append(String.format("%02x", b))
            hexString.toString()
        } catch (e: Exception) { "${filename.hashCode()}_${uri.toString().hashCode()}" }

        // Use .webp extension
        val cacheFile = File(cacheDir, "$cacheKey.webp")

        if (cacheFile.exists()) {
            return try { cacheFile.readBytes() } catch (e: Exception) { null }
        }

        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val bitmap = retriever.getFrameAtTime(2000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            
            if (bitmap != null) {
                val stream = java.io.ByteArrayOutputStream()
                // Compress as WebP (Better compression than JPEG)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                     bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 75, stream)
                } else {
                     bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 75, stream)
                }
                
                val bytes = stream.toByteArray()
                
                try {
                    val fos = java.io.FileOutputStream(cacheFile)
                    fos.write(bytes)
                    fos.close()
                } catch (e: Exception) { e.printStackTrace() }

                bitmap.recycle()
                bytes
            } else { null }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }
}
