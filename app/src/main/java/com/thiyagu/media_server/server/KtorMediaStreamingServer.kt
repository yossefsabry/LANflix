package com.thiyagu.media_server.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.provider.DocumentsContract
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val userPreferences: com.thiyagu.media_server.data.UserPreferences,
    private val port: Int
) {
    private var server: ApplicationEngine? = null

    internal val clientStatsTracker = ClientStatsTracker()
    
    // Metadata Cache Manager for fast video list retrieval
    internal val cacheManager = com.thiyagu.media_server.cache.MetadataCacheManager(appContext, treeUri)
    
    // structured concurrency scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val authManager = ServerAuthManager(userPreferences, scope)

    @Volatile private var isReady = false
    @Volatile private var readyAtMs: Long = 0L
    
    // Optimized Caches for O(1) Access and Thread Safety
    // filename -> DocumentFile
    internal val cachedFilesMap = ConcurrentHashMap<String, DocumentFile>()
    // Ordered list for pagination
    internal val allVideoFiles = java.util.Collections.synchronizedList(java.util.ArrayList<DocumentFile>())

    internal data class CachedVideoEntry(
        val name: String,
        val path: String,
        val size: Long,
        val lastModified: Long,
        val documentId: String?
    )

    private val videoIndexByPath = ConcurrentHashMap<String, CachedVideoEntry>()
    private val videoIndexByName = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    @Volatile private var videoIndexTimestamp: Long = 0L
    private val videoIndexLock = Any()
    
    // Scanning State - Delegates to MetadataCacheManager
    // We map the MetadataCacheManager.ScanStatus to the local type to maintain compatibility
    val scanStatus: kotlinx.coroutines.flow.StateFlow<ScanStatus> = cacheManager.scanStatus.map { status ->
        ScanStatus(status.isScanning, status.count)
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ScanStatus(false, 0))
    
    // Cache for directory listings (Tree Mode) - Key: Directory Uri String
    internal val directoryCache = java.util.concurrent.ConcurrentHashMap<String, CachedDirectory>()
    
    private val CACHE_DURATION_MS = 60_000L // 60 seconds
    @Volatile private var flatCacheTimestamp: Long = 0L

    data class ScanStatus(val isScanning: Boolean, val count: Int)

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
            scope.launch {
                while (currentCoroutineContext().isActive) {
                    clientStatsTracker.pruneStaleClients()
                    delay(5_000)
                }
            }

            // Build metadata cache on startup for instant client access
            // And start watching for file changes
            try {
                val cache = cacheManager.refreshIfNeeded()
                updateVideoIndex(cache)
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
                    responseWriteTimeoutSeconds = Int.MAX_VALUE
                }) {
                    install(AutoHeadResponse)
                    
                    install(io.ktor.server.plugins.compression.Compression) {
                        gzip {
                            priority = 1.0
                            matchContentType(
                                ContentType.Text.Any,
                                ContentType.Application.Json,
                                ContentType.Application.JavaScript,
                                ContentType.Application.Xml
                            )
                        }
                        deflate {
                            priority = 10.0
                            minimumSize(1024)
                            matchContentType(
                                ContentType.Text.Any,
                                ContentType.Application.Json,
                                ContentType.Application.JavaScript,
                                ContentType.Application.Xml
                            )
                        }
                    }
                    install(io.ktor.server.plugins.cachingheaders.CachingHeaders)

                    intercept(ApplicationCallPipeline.Setup) {
                        val isDiscoveryPing = call.request.headers["X-Lanflix-Discovery"] == "1" &&
                            call.request.path() == "/api/ping"
                        if (!isDiscoveryPing && authManager.isAuthorized(call)) {
                            clientStatsTracker.markSeen(getClientKey(call))
                        }
                    }
                    
                    routing {
                        configureServerRoutes(this@KtorMediaStreamingServer)
                    }
                }.start(wait = false)
                isReady = true
                readyAtMs = System.currentTimeMillis()
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
                     @Suppress("DEPRECATION")
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
        scope.launch(Dispatchers.IO) {
            val cache = cacheManager.buildCache()
            updateVideoIndex(cache)
        }
    }
    
    // Legacy scanning code removed in favor of MetadataCacheManager
    


    /**
     * Stops the running server gracefully.
     * Waits up to 1000ms for existing calls to finish, then hard stops after 5000ms.
     */
    fun stop() {
        cacheManager.stopWatching()
        isReady = false
        server?.stop(1000, 5000)
        scope.cancel()
    }

    fun isServerReady(): Boolean = isReady

    fun readySinceMs(): Long = readyAtMs

    internal fun refreshVideoIndexFromCacheIfStale(): Boolean {
        val cache = cacheManager.loadCache() ?: return false
        if (cache.folderUri != treeUri.toString()) return false
        if (cache.timestamp == videoIndexTimestamp) return true
        updateVideoIndex(cache)
        return true
    }

    internal fun getVideoEntry(path: String?, filename: String?): CachedVideoEntry? {
        if (!path.isNullOrEmpty()) {
            val byPath = videoIndexByPath[path]
            if (byPath != null) return byPath
        }
        if (!filename.isNullOrEmpty()) {
            val candidates = videoIndexByName[filename] ?: return null
            if (candidates.size == 1) {
                return videoIndexByPath[candidates[0]]
            }
        }
        return null
    }

    internal fun updateEntryFromDocumentFile(path: String?, file: DocumentFile): CachedVideoEntry? {
        val name = file.name ?: return null
        val resolvedPath = path ?: name
        val documentId = try {
            DocumentsContract.getDocumentId(file.uri)
        } catch (_: Exception) {
            null
        }
        val entry = CachedVideoEntry(
            name = name,
            path = resolvedPath,
            size = file.length(),
            lastModified = file.lastModified(),
            documentId = documentId
        )
        videoIndexByPath[resolvedPath] = entry
        videoIndexByName.computeIfAbsent(name) { CopyOnWriteArrayList() }.add(resolvedPath)
        return entry
    }

    internal fun buildDocumentUri(documentId: String): Uri {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    internal fun resolveDocumentFileByPath(path: String): DocumentFile? {
        return try {
            val rootDir = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
            var current: DocumentFile? = rootDir
            val parts = path.split("/").filter { it.isNotEmpty() }
            for (part in parts) {
                val next = current?.findFile(part)
                if (next != null) {
                    current = next
                } else {
                    return null
                }
            }
            current
        } catch (_: Exception) {
            null
        }
    }
    
    // Refresh cache only if needed (time-based)
    fun refreshCacheIfNeeded() {
        val now = System.currentTimeMillis()
        
        // Only refresh if cache is older than 60 seconds OR if it's empty (maybe first load failed)
        if (now - flatCacheTimestamp < CACHE_DURATION_MS && !allVideoFiles.isEmpty()) {
            return // Cache is fresh
        }
        
        // If scanning is already in progress, don't restart
        if (scanStatus.value.isScanning) return
        
        refreshCache()
    }
    
    fun getServerUrl(ip: String): String {
        return "http://$ip:$port/"
    }

    companion object {
        val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
    }
    
    internal fun getClientKey(call: ApplicationCall): String {
        val headerId = call.request.headers["X-Lanflix-Client"]
        val queryId = call.request.queryParameters["client"]
        val candidate = (headerId ?: queryId)?.trim()
        if (!candidate.isNullOrEmpty() && candidate.length <= 64 && candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            return candidate
        }

        val remoteAddress = call.request.local.remoteAddress
        if (remoteAddress.isNotBlank()) return remoteAddress

        val remoteHost = call.request.local.remoteHost
        if (remoteHost.isNotBlank()) return remoteHost

        return "unknown"
    }
    
    fun getConnectionStats(): ConnectionStats {
        return clientStatsTracker.getStats()
    }

    internal class TrackingInputStream(
        private val wrapped: java.io.InputStream,
        private val onClose: (() -> Unit)? = null
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
                onClose?.invoke()
            }
        }
    }

    internal class BoundedInputStream(
        private val wrapped: java.io.InputStream,
        private var remaining: Long
    ) : java.io.InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = wrapped.read()
            if (value >= 0) remaining -= 1
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = if (len.toLong() > remaining) remaining.toInt() else len
            val count = wrapped.read(b, off, toRead)
            if (count > 0) remaining -= count.toLong()
            return count
        }

        override fun skip(n: Long): Long {
            if (remaining <= 0) return 0
            val toSkip = if (n > remaining) remaining else n
            val skipped = wrapped.skip(toSkip)
            if (skipped > 0) remaining -= skipped
            return skipped
        }

        override fun available(): Int {
            val available = wrapped.available()
            val remainingInt = if (remaining > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else remaining.toInt()
            return if (remainingInt < available) remainingInt else available
        }

        override fun close() {
            wrapped.close()
        }
    }

    private fun updateVideoIndex(cache: com.thiyagu.media_server.cache.MetadataCacheManager.CacheData) {
        synchronized(videoIndexLock) {
            if (cache.folderUri != treeUri.toString()) return
            if (cache.timestamp == videoIndexTimestamp) return

            videoIndexByPath.clear()
            videoIndexByName.clear()

            cache.videos.forEach { video ->
                val entry = CachedVideoEntry(
                    name = video.name,
                    path = video.path,
                    size = video.size,
                    lastModified = video.lastModified,
                    documentId = video.documentId
                )
                videoIndexByPath[video.path] = entry
                videoIndexByName.computeIfAbsent(video.name) { CopyOnWriteArrayList() }.add(video.path)
            }

            videoIndexTimestamp = cache.timestamp
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
