package com.thiyagu.media_server.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Media Streaming Server implementation using Ktor.
 * 
 * Handles file serving, directory listing, and video streaming over HTTP.
 * Supports "Tree Mode" (SAF Tree Uri) and "Flat Mode" (Recursive scan).
 * 
 * Refactored to support Progressive Scanning and Live Status.
 * 
 * @property appContext Android Context
 * @property treeUri URI of the root directory to serve
 * @property port Server port (default 8888)
 */
class KtorMediaStreamingServer(
    internal val appContext: Context,
    internal val treeUri: Uri,
    private val port: Int
) {
    private var server: ApplicationEngine? = null
    
    // Metadata Cache Manager for fast video list retrieval
    internal val cacheManager = com.thiyagu.media_server.cache.MetadataCacheManager(appContext, treeUri)
    
    // Optimized Caches for O(1) Access and Thread Safety
    // filename -> DocumentFile
    internal val cachedFilesMap = ConcurrentHashMap<String, DocumentFile>()
    // Ordered list for pagination
    internal val allVideoFiles = java.util.Collections.synchronizedList(java.util.ArrayList<DocumentFile>())
    
    // Scanning State
    internal val isScanning = AtomicBoolean(false)
    internal val scannedCount = AtomicInteger(0)
    
    // structured concurrency scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cache for directory listings (Tree Mode) - Key: Directory Uri String
    internal val directoryCache = java.util.concurrent.ConcurrentHashMap<String, CachedDirectory>()
    
    private val CACHE_DURATION_MS = 60_000L // 60 seconds
    @Volatile private var flatCacheTimestamp: Long = 0L

    data class ScanStatus(val isScanning: Boolean, val count: Int)
    private val _scanStatus = kotlinx.coroutines.flow.MutableStateFlow(ScanStatus(false, 0))
    val scanStatus = _scanStatus.asStateFlow()

    internal data class CachedFile(
        val uri: Uri, 
        val name: String, 
        val length: Long, 
        val lastModified: Long, 
        val isDirectory: Boolean
    )

    internal data class CachedDirectory(
        val files: java.util.concurrent.CopyOnWriteArrayList<CachedFile>,
        val timestamp: Long,
        val isScanning: AtomicBoolean = AtomicBoolean(false)
    )

    /**
     * Starts the Ktor server on the configured port.
     * Initializes the cache and sets up routing headers.
     */
    fun start() {
        scope.launch {
            // Build metadata cache on startup for instant client access
            // And start watching for file changes
            try {
                cacheManager.refreshIfNeeded()
                cacheManager.startWatching()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            try {
                server = embeddedServer(Netty, port = port, configure = {
                    // Optimization for Multi-Client Streaming
                    connectionGroupSize = 2    // Dedicated threads for accepting connections
                    workerGroupSize = 16       // Threads for processing IO (Netty EventLoop)
                    callGroupSize = 32         // Threads for processing application logic (Pipeline)
                    responseWriteTimeoutSeconds = 30 // Allow ample time for writes
                }) {
                    install(PartialContent)
                    install(AutoHeadResponse)
                    
                    install(io.ktor.server.plugins.compression.Compression) {
                        gzip { priority = 1.0 }
                        deflate { priority = 10.0; minimumSize(1024) }
                    }
                    install(io.ktor.server.plugins.cachingheaders.CachingHeaders)
                    
                    routing {
                        configureServerRoutes(this@KtorMediaStreamingServer)
                    }
                }.start(wait = true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    // In-Memory LRU Cache
    private object ThumbnailMemoryCache {
        private const val MAX_SIZE = 50 * 1024 * 1024 // 50MB
        private val cache = object : android.util.LruCache<String, ByteArray>(MAX_SIZE) {
            override fun sizeOf(key: String, value: ByteArray): Int {
                return value.size
            }
        }
        fun get(key: String): ByteArray? = cache.get(key)
        fun put(key: String, value: ByteArray) { cache.put(key, value) }
    }

    private fun generateThumbnail(uri: Uri, filename: String): ByteArray? {
        val cacheDir = java.io.File(appContext.cacheDir, "thumbnails")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // Cache Key: MD5 of URI
        val cacheKey = try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            digest.update(uri.toString().toByteArray())
            val hexString = StringBuilder()
            for (b in digest.digest()) hexString.append(String.format("%02x", b))
            hexString.toString()
        } catch (e: Exception) { "${filename.hashCode()}_${uri.toString().hashCode()}" }

        // Use .webp extension
        val cacheFile = java.io.File(cacheDir, "$cacheKey.webp")

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
    
    /**
     * Refreshes the internal file cache.
     * 
     * In Tree Mode: Clears the directory listing cache.
     * In Flat Mode: Launches a background coroutine to scan recursively (Progressive Scan).
     * 
     * NOTE: This function is NON-BLOCKING and returns immediately.
     * The scanning happens asynchronously in the background.
     */
    internal fun refreshCache() {
        val dir = DocumentFile.fromTreeUri(appContext, treeUri) ?: return
        
        scope.launch(Dispatchers.IO) {
            try {
                val includeSubfolders = com.thiyagu.media_server.data.UserPreferences(appContext).subfolderScanningFlow.first()
                
                isScanning.set(true)
                _scanStatus.value = ScanStatus(true, scannedCount.get())
                
                flatCacheTimestamp = System.currentTimeMillis()
                
                val foundFiles = java.util.concurrent.ConcurrentHashMap<String, DocumentFile>()
                val existingKeys = cachedFilesMap.keys.toMutableSet()
                
                if (includeSubfolders) {
                    scanRecursivelyMarkSweep(dir, foundFiles)
                } else {
                    val files = dir.listFiles().filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm") }
                    files.forEach { file ->
                        file.name?.let { name ->
                            foundFiles[name] = file
                        }
                    }
                }
                
                // Update Main Map
                foundFiles.forEach { (name, file) ->
                    cachedFilesMap[name] = file
                }
                
                // Sweep (Remove deleted)
                val keysToRemove = existingKeys - foundFiles.keys
                keysToRemove.forEach { cachedFilesMap.remove(it) }
                
                // Rebuild List (Thread-Safe Snapshot)
                allVideoFiles.clear()
                allVideoFiles.addAll(foundFiles.values)
                scannedCount.set(allVideoFiles.size)
                
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScanning.set(false)
                _scanStatus.value = ScanStatus(false, scannedCount.get())
            }
        }
        // Returns immediately - scan continues in background!
    }
    
    private suspend fun scanRecursivelyMarkSweep(root: DocumentFile, foundaccumulator: java.util.concurrent.ConcurrentHashMap<String, DocumentFile>) {
        val stack = java.util.ArrayDeque<DocumentFile>()
        stack.push(root)
        var count = 0

        while (stack.isNotEmpty()) {
             if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
             val current = stack.pop()
             val children = current.listFiles()
             
             for (child in children) {
                 if (child.isDirectory) {
                     stack.push(child)
                 } else {
                     if (child.name?.substringAfterLast('.', "")?.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm") && child.length() > 0) {
                          child.name?.let { name ->
                              // If it's a new find, we add it. 
                              // Optimization: If we want "Live Append", we should add to `allVideoFiles` immediately if it's new.
                              // But we need to handle duplicates if we didn't clear `allVideoFiles`.
                              // Hybrid: `foundaccumulator` tracks for Sweep. 
                              // But we also check `cachedFilesMap`.
                              
                              foundaccumulator[name] = child
                              
                              if (!cachedFilesMap.containsKey(name)) {
                                  // NEW FILE DISCOVERED!
                                  cachedFilesMap[name] = child
                                  allVideoFiles.add(child) // Add to live list immediately
                              }
                              
                              count++
                              // OPTIMIZATION: Update every 5 files instead of 20 for faster client feedback
                              if (count % 5 == 0) {
                                  scannedCount.set(foundaccumulator.size)
                                  _scanStatus.value = ScanStatus(true, foundaccumulator.size)
                                  // Yield to allow API requests to be processed
                                  kotlinx.coroutines.yield()
                              }
                          }
                     }
                 }
             }
        }
        scannedCount.set(foundaccumulator.size)
    }
    


    /**
     * Stops the running server gracefully.
     * Waits up to 1000ms for existing calls to finish, then hard stops after 5000ms.
     */
    fun stop() {
        cacheManager.stopWatching()
        server?.stop(1000, 5000)
        scope.cancel()
    }
    
    // Refresh cache only if needed (time-based)
    fun refreshCacheIfNeeded() {
        val now = System.currentTimeMillis()
        
        // Only refresh if cache is older than 60 seconds OR if it's empty (maybe first load failed)
        if (now - flatCacheTimestamp < CACHE_DURATION_MS && !allVideoFiles.isEmpty()) {
            return // Cache is fresh
        }
        
        // If scanning is already in progress, don't restart
        if (isScanning.get()) return
        
        refreshCache()
    }
    
    fun getServerUrl(ip: String): String {
        return "http://$ip:$port/"
    }

    companion object {
        val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
    }

    internal class TrackingInputStream(
        private val wrapped: java.io.InputStream
    ) : java.io.InputStream() {
        
        init {
            activeConnections.incrementAndGet()
        }

        override fun read(): Int = wrapped.read()
        override fun read(b: ByteArray): Int = wrapped.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = wrapped.read(b, off, len)
        override fun skip(n: Long): Long = wrapped.skip(n)
        override fun available(): Int = wrapped.available()
        override fun markSupported(): Boolean = wrapped.markSupported()
        override fun mark(readlimit: Int) = wrapped.mark(readlimit)
        override fun reset() = wrapped.reset()

        override fun close() {
            try {
                wrapped.close()
            } finally {
                activeConnections.decrementAndGet()
            }
        }
    }
    // Helper Methods
    // Helper Methods
    internal fun getOrStartDirectoryListing(dir: DocumentFile): CachedDirectory {
        val uriKey = dir.uri.toString()
        val now = System.currentTimeMillis()
        
        var cached = directoryCache[uriKey]
        
        // If cached and fresh (less than 60s) OR currently scanning, return it
        if (cached != null) {
            if (cached.isScanning.get() || (now - cached.timestamp < CACHE_DURATION_MS)) {
                return cached
            }
        }
        
        // Create new cache entry
        val newCache = CachedDirectory(
            java.util.concurrent.CopyOnWriteArrayList(), 
            now, 
            AtomicBoolean(true) // Scanning State
        )
        directoryCache[uriKey] = newCache
        
        // Start Scan
        scope.launch(Dispatchers.IO) {
            scanDirectory(dir, newCache)
        }
        
        return newCache
    }

    private fun scanDirectory(dir: DocumentFile, cacheEntry: CachedDirectory) {
        try {
            // Use ContentResolver directly to scan without blocking
            val dirId = android.provider.DocumentsContract.getDocumentId(dir.uri)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, // Use the root tree URI for building child URIs? Actually, should use the dir URI if it's the tree root?
                // The API requires the Tree URI for buildChildDocumentsUriUsingTree.
                // If 'dir' is a subdir, we need to be careful.
                // Safest is to extract the tree authority/root from dir.uri, but we have server.treeUri which is likely the authority base.
                dirId
            )
            
            // Note: `treeUri` is the root granted URI. `dir.uri` is the specific directory URI.
            // buildChildDocumentsUriUsingTree takes (treeUri, parentDocumentId).
            
            val cols = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            
            val queryUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)

            val cursor = appContext.contentResolver.query(
                queryUri,
                cols,
                null, 
                null, 
                null
            )
            
            cursor?.use { c ->
                val idCol = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)
                val dateCol = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (c.moveToNext() && cacheEntry.isScanning.get()) {
                     val docId = c.getString(idCol)
                     val name = c.getString(nameCol) ?: "Unknown"
                     val mime = c.getString(mimeCol) ?: "application/octet-stream"
                     val size = c.getLong(sizeCol)
                     val lastMod = c.getLong(dateCol)
                     
                     val isDir = mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                     val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                     
                     cacheEntry.files.add(
                        CachedFile(
                            uri = childUri,
                            name = name,
                            length = size,
                            lastModified = lastMod,
                            isDirectory = isDir
                        )
                     )
                     
                     // Yield periodically to allow other coroutines?
                     // Not strictly needed with IO dispatcher but good etiquette
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cacheEntry.isScanning.set(false)
        }
    }
}
