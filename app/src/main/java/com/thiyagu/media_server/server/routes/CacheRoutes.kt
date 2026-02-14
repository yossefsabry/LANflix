package com.thiyagu.media_server.server.routes

import androidx.documentfile.provider.DocumentFile
import com.thiyagu.media_server.cache.MetadataCacheManager
import com.thiyagu.media_server.server.KtorMediaStreamingServer
import com.thiyagu.media_server.utils.VideoVisibilityManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

internal fun Routing.registerCacheRoutes(server: KtorMediaStreamingServer) {
    get("/api/cache") {
        if (!call.requireAuth(server)) return@get
        try {
            call.noStore()
            val cache = server.cacheManager.loadCache()
            if (cache != null) {
                call.response.headers.append("X-Lanflix-Cache-Version", cache.version.toString())
                call.response.headers.append("X-Lanflix-Cache-Timestamp", cache.timestamp.toString())
                val json = com.google.gson.Gson().toJson(cache)
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)

                if (!server.cacheManager.isCacheValid(cache)) {
                    server.refreshCache()
                }
            } else {
                server.refreshCache()
                val emptyCache = MetadataCacheManager.CacheData(
                    timestamp = System.currentTimeMillis(),
                    folderUri = server.treeUri.toString(),
                    totalVideos = 0,
                    videos = emptyList()
                )
                call.response.headers.append("X-Lanflix-Cache-Version", emptyCache.version.toString())
                call.response.headers.append("X-Lanflix-Cache-Timestamp", emptyCache.timestamp.toString())
                val json = com.google.gson.Gson().toJson(emptyCache)
                call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, e.localizedMessage ?: "Cache error")
        }
    }

    get("/api/refresh-cache") {
        if (!call.requireAuth(server)) return@get
        try {
            call.noStore()
            val newCache = server.cacheManager.buildCache()
            val json = com.google.gson.Gson().toJson(newCache)
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, e.localizedMessage ?: "Cache rebuild error")
        }
    }

    get("/api/refresh") {
        if (!call.requireAuth(server)) return@get
        call.noStore()
        server.directoryCache.clear()
        server.cacheManager.clearCache()
        server.refreshCache()
        call.respondText("Cache Refresh Started", status = HttpStatusCode.OK)
    }

    get("/api/status") {
        if (!call.requireAuth(server)) return@get
        call.noStore()
        val statusState = server.scanStatus.value
        val currentCount = statusState.count
        val scanning = statusState.isScanning

        val progress = if (scanning && currentCount > 0) {
            minOf(95, (currentCount * 3).coerceAtMost(95))
        } else if (!scanning && currentCount > 0) {
            100
        } else {
            0
        }

        val status = """{"scanning": $scanning, "count": $currentCount, "totalScanned": $currentCount, "progress": $progress}"""
        call.respondText(status, ContentType.Application.Json, HttpStatusCode.OK)
    }

    get("/api/tree") {
        if (!call.requireAuth(server)) return@get
        call.noStore()
        val pathParam = call.request.queryParameters["path"] ?: ""
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val limit = 20
        val offset = (page - 1) * limit

        val visibilityManager = VideoVisibilityManager(server.appContext)

        try {
            val rootDir = DocumentFile.fromTreeUri(server.appContext, server.treeUri)
            if (rootDir != null) {
                var currentDir = rootDir

                if (pathParam.isNotEmpty()) {
                    val parts = pathParam.split("/").filter { it.isNotEmpty() }
                    for (part in parts) {
                        val nextDir = currentDir?.findFile(part)
                        if (nextDir != null && nextDir.isDirectory) {
                            currentDir = nextDir
                        } else {
                            call.respondText("""{"items":[],"page":$page,"hasMore":false,"scanning":false}""", ContentType.Application.Json, HttpStatusCode.OK)
                            return@get
                        }
                    }
                }

                val cachedDir = server.getOrStartDirectoryListing(currentDir!!)
                val allItems = cachedDir.files.toList()
                val isScanning = cachedDir.isScanning.get()

                val filteredItems = allItems
                    .filter { !it.name.startsWith(".") }
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

    get("/api/videos") {
        if (!call.requireAuth(server)) return@get
        call.noStore()
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val limit = 20
        val offset = (page - 1) * limit

        val cacheData = server.cacheManager.loadCache()
        if (cacheData == null) {
            server.refreshCache()
            val json = """{"videos":[],"page":$page,"totalVideos":0,"hasMore":false,"scanning":true}"""
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
            return@get
        }

        val allVideos = cacheData.videos
        val filteredVideos = allVideos

        val totalVideos = filteredVideos.size
        val paginatedVideos = filteredVideos.drop(offset).take(limit)

        val videosJson = paginatedVideos.joinToString(",") { video ->
            val name = video.name
            val path = video.path
            val fileSizeFormatted = formatFileSize(video.size)
            """{"name":"${name.replace("\"", "\\\"")}","path":"${path.replace("\"", "\\\"")}","size":"$fileSizeFormatted"}"""
        }

        val json = """{"videos":[$videosJson],"page":$page,"totalVideos":$totalVideos,"hasMore":${offset + limit < totalVideos},"scanning":${server.scanStatus.value.isScanning}}"""

        call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
    }
}
