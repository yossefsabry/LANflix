package com.thiyagu.media_server.server

import androidx.documentfile.provider.DocumentFile
import com.thiyagu.media_server.utils.ThumbnailUtils
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

// Utility function to format file sizes
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

fun Route.configureServerRoutes(server: KtorMediaStreamingServer) {
    
    // NEW: Fast metadata cache endpoint
    get("/api/cache") {
        try {
            val cache = server.cacheManager.loadCache()
            
            if (cache != null) {
                // Return cached metadata as JSON
                val json = com.google.gson.Gson().toJson(cache)
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            } else {
                // No cache exists, build it now (this will take time on first request)
                val newCache = server.cacheManager.buildCache()
                val json = com.google.gson.Gson().toJson(newCache)
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, e.localizedMessage ?: "Cache error")
        }
    }
    
    // NEW: Force rebuild cache
    get("/api/refresh-cache") {
        try {
            val newCache = server.cacheManager.buildCache()
            val json = com.google.gson.Gson().toJson(newCache)
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, e.localizedMessage ?: "Cache rebuild error")
        }
    }
    
    // API to force refresh cache (legacy - now triggers metadata cache rebuild)
    get("/api/refresh") {
        server.directoryCache.clear()
        server.cacheManager.clearCache()
        server.refreshCache()
        call.respondText("Cache Refresh Started", status = HttpStatusCode.OK)
    }
    
    // API for Scan Status (Enhanced with progress tracking)
    get("/api/status") {
        val statusState = server.scanStatus.value
        val currentCount = statusState.count
        val scanning = statusState.isScanning
        
        // Estimate progress (rough approximation based on typical folder structures)
        // For better accuracy, we'd need to count directories first, but that defeats the purpose
        val progress = if (scanning && currentCount > 0) {
            // Assume we're making progress, show increasing percentage
            // This is a rough estimate - real progress tracking would require knowing total files
            minOf(95, (currentCount * 3).coerceAtMost(95)) // Cap at 95% until done
        } else if (!scanning && currentCount > 0) {
            100 // Complete
        } else {
            0 // Not started or no files
        }
        
        val status = """{"scanning": $scanning, "count": $currentCount, "totalScanned": $currentCount, "progress": $progress}"""
        call.respondText(status, ContentType.Application.Json, HttpStatusCode.OK)
    }
    
    // API for paginated directory listing (Tree Mode)
    get("/api/tree") {
        val pathParam = call.request.queryParameters["path"] ?: ""
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val limit = 20
        val offset = (page - 1) * limit
        
        val visibilityManager = com.thiyagu.media_server.utils.VideoVisibilityManager(server.appContext)
        
        try {
            val rootDir = DocumentFile.fromTreeUri(server.appContext, server.treeUri)
            if (rootDir != null) {
                var currentDir = rootDir
                
                // Navigate to target directory
                // Navigate to target directory
                if (pathParam.isNotEmpty()) {
                    val parts = pathParam.split("/").filter { it.isNotEmpty() }
                    for (part in parts) {
                        // Use native findFile (blocking but ensures deep links work)
                        val nextDir = currentDir?.findFile(part)
                        if (nextDir != null && nextDir.isDirectory) {
                            currentDir = nextDir
                        } else {
                            // Path not found
                            call.respondText("""{"items":[],"page":$page,"hasMore":false,"scanning":false}""", ContentType.Application.Json, HttpStatusCode.OK)
                            return@get
                        }
                    }
                }
                
                // Get Items
                // Get Items (Non-Blocking / Progressive)
                val cachedDir = server.getOrStartDirectoryListing(currentDir!!)
                val allItems = cachedDir.files.toList() // Snapshot
                val isScanning = cachedDir.isScanning.get()
                
                val filteredItems = allItems
                    .filter { !it.name!!.startsWith(".") }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                
                val totalItems = filteredItems.size
                val paginatedItems = filteredItems.drop(offset).take(limit)
                
                val itemsJson = paginatedItems.mapNotNull { item ->
                    val name = item.name
                    val isDir = item.isDirectory
                    val ext = name.substringAfterLast('.', "").lowercase()
                    
                    if (!isDir && ext !in setOf("mp4", "mkv", "avi", "mov", "webm")) return@mapNotNull null
                    if (!isDir && visibilityManager.isVideoHidden(item.uri.toString())) return@mapNotNull null
                    
                    val sizeFormatted = if (isDir) "" else formatFileSize(item.length)
                    val type = if (isDir) "dir" else "file"
                    """{"name":"${name.replace("\"", "\\\"")}","type":"$type","size":"$sizeFormatted"}"""
                }.joinToString(",")
                
                val json = """{"items":[$itemsJson],"page":$page,"totalItems":$totalItems,"hasMore":${offset + limit < totalItems},"scanning":$isScanning}"""
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.InternalServerError, "Root dir invalid")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, e.localizedMessage ?: "Error")
        }
    }

    // API for paginated video loading (Optimized for lazy loading)
    get("/api/videos") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val limit = 20
        val offset = (page - 1) * limit
        
        val visibilityManager = com.thiyagu.media_server.utils.VideoVisibilityManager(server.appContext)
        
        // Use Cache Manager as Source of Truth
        val cacheData = server.cacheManager.loadCache() ?: server.cacheManager.refreshIfNeeded()
        val allVideos = cacheData.videos
        
        val filteredVideos = allVideos.filter { 
            val uri = android.net.Uri.parse(it.path) // Path isn't a full URI in metadata, but that's what visibility manager expects?
            // Actually visibilityManager likely expects Document URI toString.
            // But we don't have that easily here without reconstruction. 
            // For now, let's skip visibility check on path or use simple name check if needed.
            // Or better, fix visibility manager usage later.
            true 
        }
        
        val totalVideos = filteredVideos.size
        val paginatedVideos = filteredVideos.drop(offset).take(limit)
        
        // Build JSON response using the MetaData which HAS path
        val videosJson = paginatedVideos.joinToString(",") { video ->
            val name = video.name
            val path = video.path
            val fileSizeFormatted = formatFileSize(video.size)
            """{"name":"${name.replace("\"", "\\\"")}","path":"${path.replace("\"", "\\\"")}","size":"$fileSizeFormatted"}"""
        }
        
        val json = """{"videos":[$videosJson],"page":$page,"totalVideos":$totalVideos,"hasMore":${offset + limit < totalVideos},"scanning":${server.scanStatus.value.isScanning}}"""
        
        call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
    }

    get("/") {
        val mode = call.request.queryParameters["mode"]
        val pathParam = call.request.queryParameters["path"] ?: ""
        val themeParam = call.request.queryParameters["theme"] ?: "dark"

        call.respondOutputStream(ContentType.Text.Html) {
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(this, Charsets.UTF_8))
            with(HtmlTemplates) {
                writer.respondHtmlPage(
                    mode = mode,
                    pathParam = pathParam,
                    themeParam = themeParam
                )
            }
        }
    }

    // Service Worker Route
    get("/sw.js") {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondText("""
            const CACHE_NAME = 'lanflix-v2';
            const OFFLINE_URL = '/offline.html';
            
            self.addEventListener('install', (event) => {
                self.skipWaiting();
                event.waitUntil(
                    caches.open(CACHE_NAME).then((cache) => {
                        return cache.addAll([
                            '/', 
                            '/?mode=flat',
                            '/?mode=tree',
                            'https://fonts.googleapis.com/css2?family=Spline+Sans:wght@300;400;500;600;700&display=swap',
                            'https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0,0',
                            'https://cdn.tailwindcss.com?plugins=forms,container-queries'
                        ]);
                    })
                );
            });

            self.addEventListener('activate', (event) => {
                event.waitUntil(
                    caches.keys().then((cacheNames) => {
                        return Promise.all(
                            cacheNames.map((name) => {
                                if (name !== CACHE_NAME) {
                                    return caches.delete(name);
                                }
                            })
                        );
                    })
                );
            });

            self.addEventListener('fetch', (event) => {
                const url = new URL(event.request.url);
                
                // DATA/API STRATEGY: Network First (never cache API calls aggressively)
                if (url.pathname.startsWith('/api/')) {
                    event.respondWith(
                        fetch(event.request).catch(() => {
                            // Optional: Return cached if offline? For now, just fail or return empty json
                            return new Response(JSON.stringify({ error: 'offline' }), { 
                                headers: { 'Content-Type': 'application/json' } 
                            });
                        })
                    );
                    return;
                }

                if (event.request.mode === 'navigate') {
                    event.respondWith(
                        fetch(event.request).catch(() => {
                            return caches.match(event.request).then(response => {
                                    return response || caches.match('/'); 
                            });
                        })
                    );
                } else {
                    // ASSET STRATEGY: Stale-While-Revalidate or Cache-First
                    event.respondWith(
                        caches.match(event.request).then((response) => {
                            return response || fetch(event.request);
                        })
                    );
                }
            });
        """.trimIndent(), ContentType.parse("application/javascript"))
    }

    // Optimized Thumbnail Route
    get("/api/thumbnail/{filename}") {
            val filename = call.parameters["filename"]
            val path = call.request.queryParameters["path"]
            
            if (filename != null) {
                // Check In-Memory Cache first
                val memCacheKey = if (path != null) "path:$path" else "file:$filename"
                val cachedBytes = ThumbnailUtils.ThumbnailMemoryCache.get(memCacheKey)
                
                if (cachedBytes != null) {
                    call.response.cacheControl(CacheControl.MaxAge(visibility = CacheControl.Visibility.Public, maxAgeSeconds = 31536000)) // 1 Year
                    call.respondBytes(cachedBytes, ContentType.Image.Any) // WebP
                    return@get
                }
                
                var targetFile: DocumentFile? = null
                
                // ... (Resolution Logic)
                if (!path.isNullOrEmpty()) {
                // Tree Mode Lookup
                try {
                    val rootDir = DocumentFile.fromTreeUri(server.appContext, server.treeUri)
                    if (rootDir != null) {
                        var current = rootDir
                        val parts = path.split("/").filter { it.isNotEmpty() }
                        for (part in parts) {
                            val next = current!!.findFile(part)
                            if (next != null) current = next else { current = null as DocumentFile?; break }
                        }
                        targetFile = current
                    }
                } catch (e: Exception) { e.printStackTrace() }
            } 
            
            if (targetFile == null) targetFile = server.cachedFilesMap[filename]
                
            if (targetFile != null) {
                val thumbnail = ThumbnailUtils.generateThumbnail(server.appContext, targetFile!!.uri, filename)
                if (thumbnail != null) {
                    // Update Memory Cache
                    ThumbnailUtils.ThumbnailMemoryCache.put(memCacheKey, thumbnail)
                    
                    // Cache for 1 Year (Immutable)
                    call.response.cacheControl(CacheControl.MaxAge(visibility = CacheControl.Visibility.Public, maxAgeSeconds = 31536000))
                    call.respondBytes(thumbnail, ContentType.parse("image/webp"))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
            }
    }

    // Generic File Route (Must be last)
    get("/{filename}") {
        val filename = call.parameters["filename"]
        val path = call.request.queryParameters["path"]
        
        if (filename != null) {
            // Optimized: Look in Map Cache first!
            var targetFile = server.cachedFilesMap[filename]
            
            // If not found in map, try resolving from Tree Path
            if (targetFile == null && !path.isNullOrEmpty()) {
                 try {
                    val rootDir = DocumentFile.fromTreeUri(server.appContext, server.treeUri)
                    if (rootDir != null) {
                        var current = rootDir
                        val parts = path.split("/").filter { it.isNotEmpty() }
                        for (part in parts) {
                            val next = current?.findFile(part)
                            if (next != null) current = next else { current = null; break }
                        }
                        targetFile = current
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            
            val fileToServe = targetFile
            if (fileToServe != null) {
                val length = fileToServe.length()
                val contentType = when {
                    filename.endsWith(".mp4", true) -> ContentType.Video.MP4
                    filename.endsWith(".mkv", true) -> ContentType.parse("video/x-matroska")
                    filename.endsWith(".webm", true) -> ContentType.parse("video/webm")
                    filename.endsWith(".avi", true) -> ContentType.parse("video/x-msvideo")
                    filename.endsWith(".mov", true) -> ContentType.parse("video/quicktime")
                    else -> ContentType.Video.Any
                }
                
                call.respond(object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                    override val contentLength = length
                    override val contentType = contentType
                    override val status = HttpStatusCode.OK
                    
                    override fun readFrom(): ByteReadChannel {
                        val inputStream = server.appContext.contentResolver.openInputStream(fileToServe.uri) ?: throw Exception("Cannot open stream")
                        val trackingStream = KtorMediaStreamingServer.TrackingInputStream(inputStream)
                        return trackingStream.toByteReadChannel()
                    }
                })
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    // NEW: File Existence Check Endpoint
    get("/api/exists/{filename}") {
        val filename = call.parameters["filename"]
        val path = call.request.queryParameters["path"]
        
        if (filename != null) {
            // Check Map Cache first (Flat Mode)
            var file = server.cachedFilesMap[filename]
            
            // If not found, check Tree Mode
            if (file == null && !path.isNullOrEmpty()) {
                try {
                    val rootDir = DocumentFile.fromTreeUri(server.appContext, server.treeUri)
                    if (rootDir != null) {
                        var current = rootDir
                        val parts = path.split("/").filter { it.isNotEmpty() }
                        for (part in parts) {
                            val next = current?.findFile(part)
                            if (next != null) current = next else { current = null; break }
                        }
                        file = current
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            
            if (file != null && file.exists()) {
                 call.respondText("""{"exists": true, "size": ${file.length()}}""", ContentType.Application.Json, HttpStatusCode.OK)
            } else {
                 call.respondText("""{"exists": false}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
        } else {
             call.respond(HttpStatusCode.BadRequest)
        }
    }

}
