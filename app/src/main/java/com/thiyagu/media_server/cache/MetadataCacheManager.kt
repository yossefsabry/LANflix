package com.thiyagu.media_server.cache

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Manages server-side metadata cache for fast video list retrieval.
 * 
 * Creates and maintains a JSON cache file containing all video metadata.
 * Clients fetch this file for instant access instead of triggering folder scans.
 */
class MetadataCacheManager(
    private val context: Context,
    private val treeUri: Uri
) {
    private val gson = Gson()
    
    data class ScanStatus(val isScanning: Boolean, val count: Int)
    private val _scanStatus = MutableStateFlow(ScanStatus(false, 0))
    val scanStatus = _scanStatus.asStateFlow()
    
    // Cache file stored in app's cache directory
    private val cacheFile: File
        get() = File(context.cacheDir, "lanflix_metadata_cache.json")
    
    /**
     * Main cache data structure
     */
    data class CacheData(
        @SerializedName("version")
        val version: Int = 1,
        
        @SerializedName("timestamp")
        val timestamp: Long,
        
        @SerializedName("folderUri")
        val folderUri: String,
        
        @SerializedName("totalVideos")
        val totalVideos: Int,
        
        @SerializedName("videos")
        val videos: List<VideoMetadata>
    )
    
    /**
     * Video metadata stored in cache
     */
    data class VideoMetadata(
        @SerializedName("name")
        val name: String,
        
        @SerializedName("size")
        val size: Long,
        
        @SerializedName("lastModified")
        val lastModified: Long,
        
        @SerializedName("path")
        val path: String  // Relative path from root (e.g., "subfolder/video.mp4")
    )
    
    /**
     * Build cache by scanning the entire video folder.
     * This is a potentially long operation (5-10s for 200 videos).
     */
    suspend fun buildCache(): CacheData = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoMetadata>()
        val rootDir = DocumentFile.fromTreeUri(context, treeUri)
        
        if (rootDir != null && rootDir.exists()) {
            _scanStatus.value = ScanStatus(true, 0)
            // Scan recursively
            scanRecursively(rootDir, "", videos)
        }
        
        _scanStatus.value = ScanStatus(false, videos.size)
        
        val cacheData = CacheData(
            timestamp = System.currentTimeMillis(),
            folderUri = treeUri.toString(),
            totalVideos = videos.size,
            videos = videos.sortedBy { it.name.lowercase() } // Sort for consistency
        )
        
        // Save to file
        saveCache(cacheData)
        
        cacheData
    }
    
    /**
     * Recursively scan directory for video files
     */
    private fun scanRecursively(
        dir: DocumentFile,
        relativePath: String,
        accumulator: MutableList<VideoMetadata>
    ) {
        try {
            dir.listFiles().forEach { file ->
                // Skip hidden files
                if (file.name?.startsWith(".") == true) return@forEach
                
                if (file.isDirectory) {
                    val newPath = if (relativePath.isEmpty()) file.name!! 
                                 else "$relativePath/${file.name}"
                    scanRecursively(file, newPath, accumulator)
                } else {
                    val ext = file.name?.substringAfterLast('.', "")?.lowercase()
                    if (ext in VIDEO_EXTENSIONS && file.length() > 0) {
                        accumulator.add(
                            VideoMetadata(
                                name = file.name!!,
                                size = file.length(),
                                lastModified = file.lastModified(),
                                path = if (relativePath.isEmpty()) file.name!! 
                                      else "$relativePath/${file.name}"
                            )
                        )
                        // Emit progress every 10 items to reduce overhead
                        if (accumulator.size % 10 == 0) {
                            _scanStatus.value = ScanStatus(true, accumulator.size)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Save cache data to JSON file
     */
    /**
     * Save cache data to JSON file atomically
     */
    private fun saveCache(data: CacheData) {
        try {
            val json = gson.toJson(data)
            // Atomic write: Write to temp file then rename
            val tempFile = File(cacheFile.parent, cacheFile.name + ".tmp")
            tempFile.writeText(json)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            tempFile.renameTo(cacheFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Load cache from JSON file
     */
    fun loadCache(): CacheData? {
        return try {
            if (cacheFile.exists()) {
                val json = cacheFile.readText()
                gson.fromJson(json, CacheData::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Check if cache is valid (not stale)
     * Cache is valid for 24 hours
     */
    fun isCacheValid(): Boolean {
        val cache = loadCache() ?: return false
        return isCacheValid(cache)
    }

    fun isCacheValid(cache: CacheData): Boolean {
        if (cache.folderUri != treeUri.toString()) return false
        val age = System.currentTimeMillis() - cache.timestamp
        val maxAge = 24 * 60 * 60 * 1000L // 24 hours
        return age < maxAge
    }
    
    /**
     * Refresh cache if it's stale or doesn't exist
     */
    suspend fun refreshIfNeeded(): CacheData {
        val cached = loadCache()
        if (cached != null) {
            // Check consistency inline to avoid double read
            if (cached.folderUri == treeUri.toString()) {
                val age = System.currentTimeMillis() - cached.timestamp
                if (age < 24 * 60 * 60 * 1000L) {
                    return cached
                }
            }
        }
        return buildCache()
    }
    
    /**
     * Clear cache file
     */
    fun clearCache() {
        try {
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Get cache file size in bytes
     */
    fun getCacheSize(): Long {
        return if (cacheFile.exists()) cacheFile.length() else 0
    }
    
    private var contentObserver: android.database.ContentObserver? = null
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var rebuildJob: kotlinx.coroutines.Job? = null
    
    /**
     * Start watching for changes in the video folder
     */
    fun startWatching() {
        if (contentObserver != null) return
        
        contentObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                triggerRebuild()
            }
        }
        
        try {
            context.contentResolver.registerContentObserver(treeUri, true, contentObserver!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Stop watching for changes
     */
    fun stopWatching() {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }
    
    private fun triggerRebuild() {
        // Debounce rebuilds (wait 5 seconds after last change)
        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            kotlinx.coroutines.delay(5000) // 5 seconds debounce
            try {
                buildCache()
                // Optionally notify clients via websocket if implemented
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm")
    }
}
