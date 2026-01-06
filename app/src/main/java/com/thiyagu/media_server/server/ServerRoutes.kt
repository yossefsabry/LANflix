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

fun Route.configureServerRoutes(server: KtorMediaStreamingServer) {
    
    // API to force refresh cache
    get("/api/refresh") {
        server.directoryCache.clear()
        server.refreshCache()
        call.respondText("Cache Refresh Started", status = HttpStatusCode.OK)
    }
    
    // API for Scan Status (Enhanced with progress tracking)
    get("/api/status") {
        val currentCount = server.scannedCount.get()
        val scanning = server.isScanning.get()
        val status = """{"scanning": $scanning, "count": $currentCount, "totalScanned": $currentCount}"""
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
                    
                    val size = if (isDir) 0 else item.length / (1024 * 1024)
                    val type = if (isDir) "dir" else "file"
                    """{"name":"${name.replace("\"", "\\\"")}","type":"$type","size":$size}"""
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
        
        // Trigger cache refresh on first request if cache is empty
        if (page == 1 && server.allVideoFiles.isEmpty() && !server.isScanning.get()) {
            server.refreshCache() // Returns immediately, scan runs in background
        }
        
        // Snapshot of current list
        val currentVideos = synchronized(server.allVideoFiles) { server.allVideoFiles.toList() }
        
        val filteredVideos = currentVideos.filter { 
            !visibilityManager.isVideoHidden(it.uri.toString())
        }
        
        val totalVideos = filteredVideos.size
        val paginatedVideos = filteredVideos.drop(offset).take(limit)
        
        // Build JSON response
        val videosJson = paginatedVideos.joinToString(",") { file ->
            val name = file.name ?: "Unknown"
            val fileSize = file.length() / (1024 * 1024)
            """{"name":"${name.replace("\"", "\\\"")}","size":$fileSize}"""
        }
        
        val json = """{"videos":[$videosJson],"page":$page,"totalVideos":$totalVideos,"hasMore":${offset + limit < totalVideos},"scanning":${server.isScanning.get()}}"""
        
        call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
    }

    get("/") {
        val mode = call.request.queryParameters["mode"]
        val pathParam = call.request.queryParameters["path"] ?: ""
        val pageParam = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val themeParam = call.request.queryParameters["theme"] ?: "dark"

        
        val visibilityManager = com.thiyagu.media_server.utils.VideoVisibilityManager(server.appContext)

        call.respondOutputStream(ContentType.Text.Html) {
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(this, Charsets.UTF_8))
            with(HtmlTemplates) {
                writer.respondHtmlPage(
                    appContext = server.appContext,
                    treeUri = server.treeUri,
                    mode = mode,
                    pathParam = pathParam,
                    pageParam = pageParam,
                    themeParam = themeParam,
                    visibilityManager = visibilityManager,
                    allVideoFiles = synchronized(server.allVideoFiles) { server.allVideoFiles.toList() }
                )
            }
        }
    }

    // Service Worker Route
    get("/sw.js") {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondText("""
            const CACHE_NAME = 'lanflix-v1';
            const OFFLINE_URL = '/offline.html';
            
            self.addEventListener('install', (event) => {
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

            self.addEventListener('fetch', (event) => {
                if (event.request.mode === 'navigate') {
                    event.respondWith(
                        fetch(event.request).catch(() => {
                            return caches.match(event.request).then(response => {
                                    return response || caches.match('/'); 
                            });
                        })
                    );
                } else {
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
        if (filename != null) {
            // Optimized: Look in Map Cache first!
            val targetFile = server.cachedFilesMap[filename]
            
            if (targetFile != null) {
                val length = targetFile.length()
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
                        val inputStream = server.appContext.contentResolver.openInputStream(targetFile.uri) ?: throw Exception("Cannot open stream")
                        val trackingStream = KtorMediaStreamingServer.TrackingInputStream(inputStream)
                        return trackingStream.toByteReadChannel()
                    }
                })
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
