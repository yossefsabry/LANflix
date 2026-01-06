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
    private val appContext: Context,
    private val treeUri: Uri,
    private val port: Int
) {
    private var server: ApplicationEngine? = null
    
    // Optimized Caches for O(1) Access and Thread Safety
    // filename -> DocumentFile
    private val cachedFilesMap = ConcurrentHashMap<String, DocumentFile>()
    // Ordered list for pagination
    private val allVideoFiles = java.util.Collections.synchronizedList(java.util.ArrayList<DocumentFile>())
    
    // Scanning State
    private val isScanning = AtomicBoolean(false)
    private val scannedCount = AtomicInteger(0)
    
    // structured concurrency scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cache for directory listings (Tree Mode) - Key: Directory Uri String
    private val directoryCache = java.util.concurrent.ConcurrentHashMap<String, CachedDirectory>()
    
    private val CACHE_DURATION_MS = 60_000L // 60 seconds
    @Volatile private var flatCacheTimestamp: Long = 0L

    data class ScanStatus(val isScanning: Boolean, val count: Int)
    private val _scanStatus = kotlinx.coroutines.flow.MutableStateFlow(ScanStatus(false, 0))
    val scanStatus = _scanStatus.asStateFlow()

    private data class CachedDirectory(
        val files: List<DocumentFile>,
        val timestamp: Long
    )

    /**
     * Starts the Ktor server on the configured port.
     * Initializes the cache and sets up routing headers.
     */
    fun start() {
        scope.launch {
            // Initial scan (Progressive)
            refreshCache()
            
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
                        // API to force refresh cache
                        get("/api/refresh") {
                            directoryCache.clear()
                            refreshCache()
                            call.respondText("Cache Refresh Started", status = HttpStatusCode.OK)
                        }
                        
                        // API for Scan Status
                        get("/api/status") {
                            val status = """{"scanning": ${isScanning.get()}, "count": ${scannedCount.get()}}"""
                            call.respondText(status, ContentType.Application.Json, HttpStatusCode.OK)
                        }
                        
                        // API for paginated directory listing (Tree Mode)
                        get("/api/tree") {
                            val pathParam = call.request.queryParameters["path"] ?: ""
                            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                            val limit = 20
                            val offset = (page - 1) * limit
                            
                            val visibilityManager = com.thiyagu.media_server.utils.VideoVisibilityManager(appContext)
                            
                            try {
                                val rootDir = DocumentFile.fromTreeUri(appContext, treeUri)
                                if (rootDir != null) {
                                    var currentDir = rootDir
                                    
                                    // Navigate to target directory
                                    if (pathParam.isNotEmpty()) {
                                        val parts = pathParam.split("/").filter { it.isNotEmpty() }
                                        for (part in parts) {
                                            val items = getDirectoryListing(currentDir!!)
                                            val nextDir = items.find { it.isDirectory && it.name == part }
                                            if (nextDir != null) {
                                                currentDir = nextDir
                                            } else {
                                                // Path not found
                                                call.respondText("""{"items":[],"page":$page,"hasMore":false}""", ContentType.Application.Json, HttpStatusCode.OK)
                                                return@get
                                            }
                                        }
                                    }
                                    
                                    // Get Items
                                    val allItems = getDirectoryListing(currentDir!!)
                                        .filter { !it.name!!.startsWith(".") }
                                        .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                                    
                                    val totalItems = allItems.size
                                    val paginatedItems = allItems.drop(offset).take(limit)
                                    
                                    val itemsJson = paginatedItems.mapNotNull { item ->
                                        val name = item.name ?: return@mapNotNull null
                                        val isDir = item.isDirectory
                                        if (!isDir && !name.substringAfterLast('.', "").lowercase().let { it in setOf("mp4", "mkv", "avi", "mov", "webm") }) return@mapNotNull null
                                        if (!isDir && visibilityManager.isVideoHidden(item.uri.toString())) return@mapNotNull null
                                        
                                        val size = if (isDir) 0 else item.length() / (1024 * 1024)
                                        val type = if (isDir) "dir" else "file"
                                        """{"name":"${name.replace("\"", "\\\"")}","type":"$type","size":$size}"""
                                    }.joinToString(",")
                                    
                                    val json = """{"items":[$itemsJson],"page":$page,"totalItems":$totalItems,"hasMore":${offset + limit < totalItems}}"""
                                    call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
                                } else {
                                    call.respond(HttpStatusCode.InternalServerError, "Root dir invalid")
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                call.respond(HttpStatusCode.InternalServerError, e.localizedMessage ?: "Error")
                            }
                        }

                        // API for paginated video loading
                        get("/api/videos") {
                            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                            val limit = 20
                            val offset = (page - 1) * limit
                            
                            val visibilityManager = com.thiyagu.media_server.utils.VideoVisibilityManager(appContext)
                            
                            // Snapshot of current list
                            val currentVideos = synchronized(allVideoFiles) { allVideoFiles.toList() }
                            
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
                            
                            val json = """{"videos":[$videosJson],"page":$page,"totalVideos":$totalVideos,"hasMore":${offset + limit < totalVideos}}"""
                            
                            call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
                        }

                        get("/") {
                            val mode = call.request.queryParameters["mode"]
                            val pathParam = call.request.queryParameters["path"] ?: ""
                            val pageParam = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                            val themeParam = call.request.queryParameters["theme"] ?: "dark"
                            val limit = 20
                            
                            val visibilityManager = com.thiyagu.media_server.utils.VideoVisibilityManager(appContext)

                            call.respondOutputStream(ContentType.Text.Html) {
                                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(this, Charsets.UTF_8))
                                
                                // --- HTML HEAD ---
                                writer.write("""
<!DOCTYPE html>
<html lang="en" class="${if (themeParam == "dark") "dark" else ""}">
<head>
    <meta charset="utf-8"/>
    <meta content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover" name="viewport"/>
    <meta content="${if (themeParam == "dark") "#0a0a0a" else "#F4F4F5"}" name="theme-color"/>
    <title>LANflix</title>
    <link href="https://fonts.googleapis.com/css2?family=Spline+Sans:wght@300;400;500;600;700&display=swap" rel="stylesheet"/>
    <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0,0" rel="stylesheet"/>
    <script src="https://cdn.tailwindcss.com?plugins=forms,container-queries"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: ${if (themeParam == "dark") """
                        {
                            primary: "#FAC638",
                            background: "#0a0a0a",
                            surface: "#171717",
                            "surface-light": "#262626",
                            "text-main": "#f2f2f2",
                            "text-sub": "#9ca3af"
                        }
                    """ else """
                        {
                            primary: "#D4A526",
                            background: "#F4F4F5",
                            surface: "#FFFFFF",
                            "surface-light": "#F9FAFB",
                            "text-main": "#09090B",
                            "text-sub": "#71717A"
                        }
                    """},
                    fontFamily: { 
                        display: ['Spline Sans', 'sans-serif'] 
                    }
                }
            }
        };
    </script>
    <style>
        :root {
            --color-primary: ${if (themeParam == "dark") "#FAC638" else "#D4A526"};
        }
        body { 
            font-family: 'Spline Sans', sans-serif; 
            -webkit-tap-highlight-color: transparent; 
            -webkit-font-smoothing: antialiased;
        }
        .hide-scrollbar::-webkit-scrollbar { display: none; }
        .hide-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }
        .progress-bar-container {
             position: fixed;
             top: 0;
             left: 0;
             right: 0;
             height: 5px;
             z-index: 100;
             pointer-events: none;
             transition: transform 0.3s ease;
             transform: translateY(-100%);
             background: rgba(255,255,255,0.1);
             overflow: hidden;
        }
        .progress-bar-container.visible {
            transform: translateY(0);
        }
        .progress-bar-fill {
            height: 100%;
            background-color: var(--color-primary, #D4A526); /* Fallback */
            width: 30%;
            animation: indeterminate 2s infinite linear;
        }
        @keyframes indeterminate {
            0% { transform: translateX(-100%); width: 30%; }
            50% { width: 60%; }
            100% { transform: translateX(200%); width: 30%; }
        }
        @keyframes wobble {
            0% { transform: translateX(0%); }
            15% { transform: translateX(-25%) rotate(-5deg); }
            30% { transform: translateX(20%) rotate(3deg); }
            45% { transform: translateX(-15%) rotate(-3deg); }
            60% { transform: translateX(10%) rotate(2deg); }
            75% { transform: translateX(-5%) rotate(-1deg); }
            100% { transform: translateX(0%); }
        }
        
        /* Skeleton Loading */
        .skeleton {
            background: linear-gradient(90deg, #ffffff05 25%, #ffffff10 50%, #ffffff05 75%);
            background-size: 200% 100%;
            animation: shimmer 1.5s infinite;
            border-radius: 12px;
        }
        @keyframes shimmer {
            0% { background-position: 200% 0; }
            100% { background-position: -200% 0; }
        }

        /* Responsive Grid */
        .auto-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
            gap: 1rem;
        }

        /* View Transitions */
        ::view-transition-old(root),
        ::view-transition-new(root) {
            animation-duration: 0.3s;
        }
    </style>
    <script>
        // Theme & Persistence Logic
        (function() {
            // Check LocalStorage for theme preference
            const savedTheme = localStorage.getItem('lanflix_theme');
            const urlParams = new URLSearchParams(window.location.search);
            const urlTheme = urlParams.get('theme');
            const mode = urlParams.get('mode');
            const path = urlParams.get('path');
            
            // If URL has no theme but we have a saved one, redirect to apply it (Server-Side Rendering needs it)
            // Or better: Apply it via JS immediately to body class, but SSR color variables might be wrong.
            // Since we rely on server-injected colors, we MUST reload/redirect if the server rendered the wrong theme.
            // But to avoid loops, only redirect if they differ.
            
            let targetTheme = urlTheme || savedTheme || 'dark'; // Default to dark
            
            // Save initial state
            if (urlTheme) localStorage.setItem('lanflix_theme', urlTheme);
            if (mode) localStorage.setItem('lanflix_last_mode', mode);
            if (mode === 'tree' && path !== null) localStorage.setItem('lanflix_last_path', path);
            
            // Redirect if needed
            if (!urlTheme && savedTheme) {
                 // Build new URL
                 urlParams.set('theme', savedTheme);
                 window.location.replace('?' + urlParams.toString());
            }

            // Restore State for Root Visit
            if (!window.location.search) {
                const lastMode = localStorage.getItem('lanflix_last_mode');
                if (lastMode === 'tree') {
                   const lastPath = localStorage.getItem('lanflix_last_path');
                   window.location.replace('/?mode=tree&theme=' + targetTheme + (lastPath ? '&path=' + encodeURIComponent(lastPath) : ''));
                } else {
                   window.location.replace('/?theme=' + targetTheme);
                }
            }
        })();

        function toggleTheme() {
            const current = document.documentElement.classList.contains('dark') ? 'dark' : 'light';
            const next = current === 'dark' ? 'light' : 'dark';
            localStorage.setItem('lanflix_theme', next);
            
            // Reload with new theme param
            const url = new URL(window.location);
            url.searchParams.set('theme', next);
            window.location.href = url.toString();
        }
    </script>
    <script>
        if ('serviceWorker' in navigator) {
            window.addEventListener('load', () => {
                navigator.serviceWorker.register('/sw.js').catch(err => console.log('SW Registration failed:', err));
            });
        }
    </script>
    <style>
        /* Disconnect Overlay */
        #connection-lost {
            position: fixed;
            inset: 0;
            z-index: 200;
            background: rgba(0,0,0,0.8);
            backdrop-filter: blur(8px);
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            opacity: 0;
            pointer-events: none;
            transition: opacity 0.3s ease;
        }
        #connection-lost.visible {
            opacity: 1;
            pointer-events: auto;
        }
    </style>
    <script>
        // Lazy load thumbnails
        if ('IntersectionObserver' in window) {
            window.imageObserver = new IntersectionObserver((entries, observer) => {
                entries.forEach(entry => {
                    if (entry.isIntersecting) {
                        const img = entry.target;
                        img.src = img.dataset.src;
                        img.classList.remove('opacity-0');
                        observer.unobserve(img);
                    }
                });
            }, { rootMargin: '50px 0px', threshold: 0.01 });

            document.addEventListener('DOMContentLoaded', () => {
                document.querySelectorAll('img[loading="lazy"]').forEach(img => window.imageObserver.observe(img));
            });
        }
    </script>
</head>
<body class="bg-background text-text-main min-h-screen pb-24 selection:bg-primary selection:text-black">
    
    <!-- Connection Lost Overlay -->
    <div id="connection-lost">
        <div class="bg-surface p-6 rounded-2xl flex flex-col items-center gap-4 text-center border border-white/10 shadow-2xl max-w-xs mx-4">
            <div class="w-12 h-12 rounded-full bg-red-500/20 flex items-center justify-center">
                <span class="material-symbols-rounded text-red-500 text-2xl">wifi_off</span>
            </div>
            <div>
                <h3 class="font-bold text-lg text-text-main">Connection Lost</h3>
                <p class="text-sm text-text-sub mt-1">Reconnecting to server...</p>
            </div>
            <div class="w-6 h-6 border-2 border-primary border-t-transparent rounded-full animate-spin mt-2"></div>
        </div>
    </div>

    <!-- Sticky Header -->
    <header class="sticky top-0 z-50 bg-background/95 backdrop-blur-md px-4 py-3 border-b border-white/5">
        <div class="flex items-center justify-between">
            <a href="http://exit" class="w-10 h-10 rounded-full bg-surface-light border border-white/5 flex items-center justify-center active:scale-95 transition-transform">
                 <span class="material-symbols-rounded text-text-sub">arrow_back</span>
            </a>
            <div class="flex items-center gap-2">
                <span class="material-symbols-rounded text-primary text-2xl">play_circle</span>
                <h1 class="text-xl font-bold tracking-tight">LANflix</h1>
            </div>
            <div class="flex gap-2">
                <button onclick="toggleTheme()" class="w-10 h-10 rounded-full bg-surface-light border border-white/5 flex items-center justify-center active:scale-95 transition-transform text-text-sub">
                    <span class="material-symbols-rounded">${if (themeParam == "dark") "light_mode" else "dark_mode"}</span>
                </button>
                <a href="http://profile" class="w-10 h-10 rounded-full bg-surface-light border border-white/5 flex items-center justify-center active:scale-95 transition-transform relative">
                     <span class="material-symbols-rounded text-text-sub">person</span>
                     <div class="absolute top-0 right-0 w-2.5 h-2.5 bg-green-500 rounded-full border-2 border-background"></div>
                </a>
            </div>
        </div>
    </header>

    <!-- Scanning Progress Bar -->
    <div id="scanning-bar" class="progress-bar-container">
        <div class="progress-bar-fill bg-primary"></div>
    </div>

    <main class="px-4 py-4 space-y-6">
""")
                                
                                // --- CONTENT GENERATION ---
                                withContext(Dispatchers.IO) {
                                    if (mode == "tree") {
                                        // --- TREE BROWSER MODE ---
                                        val rootDir = DocumentFile.fromTreeUri(appContext, treeUri)
                                        var currentDir = rootDir
                                        
                                        val breadcrumbs = mutableListOf<Pair<String, String>>()
                                        breadcrumbs.add("Home" to "")
                                        
                                        var currentPathBuilder = ""
                                        // Path Resolution with Cache
                                        if (pathParam.isNotEmpty() && rootDir != null) {
                                            val parts = pathParam.split("/").filter { it.isNotEmpty() }
                                            
                                            for (part in parts) {
                                                // Check cache or list
                                                val items = getDirectoryListing(currentDir!!)
                                                val nextDir = items.find { it.isDirectory && it.name == part }
                                                
                                                if (nextDir != null) {
                                                    currentDir = nextDir
                                                    currentPathBuilder += "/$part"
                                                    breadcrumbs.add(part to currentPathBuilder)
                                                } else {
                                                    break 
                                                }
                                            }
                                        }

                                        val dir = currentDir
                                        if (dir == null || !dir.isDirectory) {
                                            writer.write("""<div class="w-full text-center py-20 text-text-sub">Directory not found</div>""")
                                        } else {
                                            val allItems = getDirectoryListing(dir)
                                                .filter { !it.name!!.startsWith(".") }
                                                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                                            
                                            val totalItems = allItems.size
                                            val totalPages = (totalItems + limit - 1) / limit
                                            val pagedItems = allItems.drop((pageParam - 1) * limit).take(limit)

                                            // Breadcrumbs
                                            writer.write("""<div class="flex items-center gap-2 mb-4 overflow-x-auto whitespace-nowrap px-1">""")
                                            breadcrumbs.forEachIndexed { index, (name, path) ->
                                                if (index > 0) writer.write("""<span class="text-text-sub">/</span>""")
                                                val isLast = index == breadcrumbs.size - 1
                                                val color = if (isLast) "text-primary font-bold" else "text-text-sub hover:text-text-main"
                                                writer.write("""<a href="/?mode=tree&path=$path" class="$color text-sm">$name</a>""")
                                            }
                                            writer.write("""</div>""")

                                            // Grid
                                            writer.write("""<div id="tree-grid" class="grid grid-cols-2 gap-4">""")
                                            
                                            for (item in pagedItems) {
                                                val name = item.name ?: "Unknown"
                                                if (item.isDirectory) {
                                                    val newPath = if (pathParam.isEmpty()) "/$name" else "$pathParam/$name"
                                                    writer.write("""
                                                        <a href="/?mode=tree&path=${newPath.replace("\"", "&quot;")}" class="bg-surface-light rounded-xl p-4 flex flex-col items-center gap-2 border border-white/5 active:scale-95 transition-transform">
                                                            <span class="material-symbols-rounded text-4xl text-primary">folder</span>
                                                            <span class="text-xs text-center font-medium line-clamp-1 break-all">$name</span>
                                                        </a>
                                                    """)
                                                } else {
                                                     if (name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".avi", true) || name.endsWith(".webm", true)) {
                                                         if (!visibilityManager.isVideoHidden(item.uri.toString())) {
                                                             val fileSize = item.length() / (1024 * 1024)
                                                             val itemPath = if (pathParam.isEmpty()) "/$name" else "$pathParam/$name"
                                                             
                                                             // Reuse previous logic for MODE check
                                                             // This block is for server-side rendering of the initial page.
                                                             // The JS `createVideoCard` is for client-side dynamic loading.
                                                             // For SSR, we directly render the HTML.
                                                             writer.write("""
                                                                <a href="/${name}" class="group block bg-surface-light rounded-2xl p-2 border border-white/5 active:scale-95 transition-transform">
                                                                     <div class="relative aspect-[4/3] rounded-xl overflow-hidden bg-background mb-3">
                                                                         <img data-src="/api/thumbnail/${name}?path=${itemPath}" src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxIDEiPjwvc3ZnPg==" loading="lazy" class="absolute inset-0 w-full h-full object-cover opacity-0 transition-opacity duration-500"/>
                                                                         <div class="absolute inset-0 flex items-center justify-center -z-10 bg-surface">
                                                                             <span class="material-symbols-rounded text-3xl text-text-sub/20 group-hover:text-primary transition-colors">movie</span>
                                                                         </div>
                                                                         <div class="absolute bottom-2 right-2 w-8 h-8 rounded-full bg-black/60 backdrop-blur flex items-center justify-center">
                                                                             <span class="material-symbols-rounded text-white text-lg">play_arrow</span>
                                                                         </div>
                                                                     </div>
                                                                     <div class="px-1 pb-1">
                                                                         <h3 class="font-bold text-sm text-text-main line-clamp-2 leading-snug mb-1">${name}</h3>
                                                                         <p class="text-[11px] text-text-sub">${fileSize} MB</p>
                                                                     </div>
                                                                </a>
                                                             """)
                                                         }
                                                    }
                                                }
                                            }
                                            writer.write("""</div>""")
                                            
                                            // Loading Indicators & Sentinel for Infinite Scroll
                                            writer.write("""
                                                <div id="loading-indicator" class="hidden col-span-full flex justify-center py-8">
                                                    <div class="flex items-center gap-3 text-text-sub">
                                                        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
                                                    </div>
                                                </div>
                                                <div id="loading-sentinel"></div>
                                            """)
                                        }

                                    } else {
                                        // --- DEFAULT FLAT MODE (Live/Progressive) ---
                                        
                                        // No directory check needed here (scanning or cached)
                                        // Simply show what we have so far
                                        
                                        val currentFiles = synchronized(allVideoFiles) { allVideoFiles.toList() }
                                        val filteredFiles = currentFiles.filter { 
                                            !visibilityManager.isVideoHidden(it.uri.toString())
                                        }
    
                                        val recentFiles = filteredFiles.sortedByDescending { it.lastModified() }.take(5)
                    
                                        // Recently Added
                                        writer.write("""
                                        <div class="relative mb-6">
                                            <span class="material-symbols-rounded absolute left-4 top-1/2 -translate-y-1/2 text-text-sub">search</span>
                                            <input type="text" placeholder="Search local videos..." 
                                                class="w-full bg-surface-light border border-white/5 rounded-full py-3.5 pl-12 pr-12 text-sm placeholder:text-text-sub/50 focus:ring-1 focus:ring-primary focus:border-primary/50 transition-all outline-none text-text-main">
                                            <button class="absolute right-2 top-1/2 -translate-y-1/2 p-2 rounded-full hover:bg-white/5 text-text-sub">
                                                <span class="material-symbols-rounded text-[20px]">tune</span>
                                            </button>
                                        </div>
                                        <section>
                                            <div class="flex items-center justify-between mb-4">
                                                 <h2 class="text-lg font-bold">Recently Added</h2>
                                                 <button onclick="document.getElementById('all-videos').scrollIntoView({behavior: 'smooth'})" class="text-xs font-bold text-primary">See All</button>
                                            </div>
                                            <div class="flex gap-4 overflow-x-auto hide-scrollbar -mx-4 px-4 scroll-pl-4 snap-x">""")
                                        
                                        for (file in recentFiles) {
                                            val name = file.name ?: "Unknown"
                                            val fileSize = file.length() / (1024 * 1024)
                                            writer.write("""
                                                <a href="/${name}" class="flex-none w-64 snap-start group relative rounded-2xl overflow-hidden aspect-video bg-surface-light border border-white/5">
                                                    <img data-src="/api/thumbnail/${name}" src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxIDEiPjwvc3ZnPg==" loading="lazy" class="absolute inset-0 w-full h-full object-cover opacity-0 transition-opacity duration-500"/>
                                                    <div class="absolute inset-0 bg-black/20 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity z-10">
                                                        <div class="w-12 h-12 bg-white/20 backdrop-blur-sm rounded-full flex items-center justify-center"><span class="material-symbols-rounded text-white text-3xl">play_arrow</span></div>
                                                    </div>
                                                    <div class="absolute inset-0 flex items-center justify-center bg-white/5 -z-10"><span class="material-symbols-rounded text-4xl text-text-sub/20">movie</span></div>
                                                    <div class="absolute inset-0 bg-gradient-to-t from-black/90 via-black/20 to-transparent"></div>
                                                    <div class="absolute bottom-3 left-3 right-3">
                                                        <h3 class="font-bold text-sm text-white line-clamp-1 mb-1">${name}</h3>
                                                        <div class="flex items-center gap-2 text-[10px] text-gray-300 font-medium">
                                                            <span class="bg-primary/20 text-primary px-1.5 py-0.5 rounded">NEW</span>
                                                            <span>${fileSize} MB</span>
                                                        </div>
                                                    </div>
                                                </a>""")
                                        }
                                        if (recentFiles.isEmpty()) writer.write("""<div class="w-full text-center py-8 text-text-sub text-sm">No recent videos</div>""")
                                        writer.write("""</div></section>""")
                                        
                                        // All Videos - Progressive Loading
                                        writer.write("""
                                        <section class="mt-6">
                                            <div class="flex items-center justify-between mb-4">
                                                 <h2 id="all-videos" class="text-lg font-bold">All Videos</h2>
                                                 <span id="video-count" class="text-xs text-text-sub/60 font-medium">Loading...</span>
                                            </div>
                                            <div id="video-grid" class="auto-grid pb-8">
                                                <!-- Skeleton loaders for immediate feedback -->
                                                <div class="skeleton-card animate-pulse bg-surface-light rounded-2xl p-2 border border-white/5">
                                                    <div class="aspect-[4/3] rounded-xl bg-background mb-3"></div>
                                                   <div class="px-1 pb-1 space-y-2">
                                                        <div class="h-4 bg-background rounded w-3/4"></div>
                                                        <div class="h-3 bg-background rounded w-1/2"></div>
                                                    </div>
                                                </div>
                                                <div class="skeleton-card animate-pulse bg-surface-light rounded-2xl p-2 border border-white/5">
                                                    <div class="aspect-[4/3] rounded-xl bg-background mb-3"></div>
                                                    <div class="px-1 pb-1 space-y-2">
                                                        <div class="h-4 bg-background rounded w-3/4"></div>
                                                        <div class="h-3 bg-background rounded w-1/2"></div>
                                                    </div>
                                                </div>
                                            </div>
                                            <!-- Loading indicator -->
                                            <div id="loading-indicator" class="hidden col-span-full flex justify-center py-8">
                                                <div class="flex items-center gap-3 text-text-sub">
                                                    <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
                                                </div>
                                            </div>
                                            <!-- Sentinel for infinite scroll -->
                                            <div id="loading-sentinel"></div>
                                        </section>
                                        

                                        <script>
                                            const MODE = "${mode ?: "flat"}";
                                            const PATH = "${pathParam.replace("\"", "\\\"")}";
                                            let currentPage = ${if (mode == "tree") 1 else 1}; // Always start at 1 for JS load? No, SSR might load pg 1.
                                            // Actually, if SSR loads Page 1, we should start lazy loading at Page 2.
                                            // The original code had SSR load Page 1.
                                            currentPage = 2; // We assume Page 1 is already rendered by SSR
                                            
                                            let isLoading = false;
                                            let hasMore = true; // Assume true initially? Or check SSR count? 
                                            // SSR Render logic above calculated total pages. We can inject "hasMore" state?
                                            // For simplicity, let's just attempt to load page 2 if we think we might have more.
                                            
                                            // But for Tree Mode SSR, we rendered Page 1.
                                            // For Flat Mode SSR, we rendered "Recent" and maybe "All" skeleton?
                                            // Actually Flat Mode SSR renders skeletons and JS loads Page 1.
                                            
                                            if (MODE === 'tree') {
                                                // In Tree mode, SSR rendered Page 1. So we start at 2.
                                                currentPage = 2;
                                            } else {
                                                // In Flat mode, SSR rendered skeletons. JS loads Page 1.
                                                currentPage = 1; 
                                            }
                                            
                                            // IndexedDB Cache Manager
                                            const CacheManager = {
                                                dbName: 'LANflixDB',
                                                version: 2,
                                                storeName: 'items', // For Flat Mode (All Videos)
                                                treeStoreName: 'tree_items', // For Tree Mode (Directory Listings)
                                                
                                                async open() {
                                                    return new Promise((resolve, reject) => {
                                                        const request = indexedDB.open(this.dbName, this.version);
                                                        request.onupgradeneeded = (event) => {
                                                            const db = event.target.result;
                                                            // V1: Flat Store
                                                            if (!db.objectStoreNames.contains(this.storeName)) {
                                                                db.createObjectStore(this.storeName, { keyPath: 'name' });
                                                            }
                                                            // V2: Tree Store
                                                            if (!db.objectStoreNames.contains(this.treeStoreName)) {
                                                                db.createObjectStore(this.treeStoreName, { keyPath: 'path' });
                                                            }
                                                        };
                                                        request.onsuccess = () => resolve(request.result);
                                                        request.onerror = (e) => reject(e);
                                                    });
                                                },
                                                
                                                async getAll() {
                                                    const db = await this.open();
                                                    return new Promise((resolve, reject) => {
                                                        const tx = db.transaction(this.storeName, 'readonly');
                                                        const store = tx.objectStore(this.storeName);
                                                        const request = store.getAll();
                                                        request.onsuccess = () => resolve(request.result);
                                                        request.onerror = () => reject(request.error);
                                                    });
                                                },
                                                
                                                async save(items) {
                                                    if (!items || items.length === 0) return;
                                                    const db = await this.open();
                                                    const tx = db.transaction(this.storeName, 'readwrite');
                                                    const store = tx.objectStore(this.storeName);
                                                    items.forEach(item => {
                                                        try { store.put(item); } catch (e) {}
                                                    });
                                                },
                                                
                                                async getTree(path) {
                                                    const db = await this.open();
                                                    return new Promise((resolve, reject) => {
                                                        const tx = db.transaction(this.treeStoreName, 'readonly');
                                                        const store = tx.objectStore(this.treeStoreName);
                                                        const request = store.get(path);
                                                        request.onsuccess = () => resolve(request.result ? request.result.items : []);
                                                        request.onerror = () => reject(request.error);
                                                    });
                                                },

                                                async saveTree(path, items) {
                                                    if (!items) return;
                                                    const db = await this.open();
                                                    const tx = db.transaction(this.treeStoreName, 'readwrite');
                                                    const store = tx.objectStore(this.treeStoreName);
                                                    store.put({ path: path, items: items, timestamp: Date.now() });
                                                },
                                                
                                                async clear() {
                                                     const db = await this.open();
                                                     const tx = db.transaction([this.storeName, this.treeStoreName], 'readwrite');
                                                     tx.objectStore(this.storeName).clear();
                                                     tx.objectStore(this.treeStoreName).clear();
                                                }
                                            };
                                            
                                            let pollInterval = null;
                                            
                                            // Progress Polling
                                            function startPolling() {
                                                const bar = document.getElementById('scanning-bar');
                                                
                                                pollInterval = setInterval(async () => {
                                                    try {
                                                        const res = await fetch('/api/status');
                                                        const status = await res.json();
                                                        
                                                        // Recovered
                                                        document.getElementById('connection-lost').classList.remove('visible');
                                                        
                                                        if (status.scanning) {
                                                            bar.classList.add('visible');
                                                            if (MODE === 'flat' && currentPage === 1) {
                                                                loadContent(); 
                                                            }
                                                        } else {
                                                            bar.classList.remove('visible');
                                                            clearInterval(pollInterval);
                                                            if (MODE === 'flat' && currentPage === 1) loadContent();
                                                        }
                                                    } catch (e) { 
                                                        console.error(e); 
                                                        // Connection lost?
                                                        document.getElementById('connection-lost').classList.add('visible');
                                                    }
                                                }, 2000);
                                            }
                                            
                                            startPolling();

                                            async function fetchWithRetry(url, retries = 3, delay = 1000) {
                                                try {
                                                    const response = await fetch(url);
                                                    if (!response.ok) throw new Error('Request failed');
                                                    return response;
                                                } catch (err) {
                                                    if (retries === 0) throw err;
                                                    await new Promise(resolve => setTimeout(resolve, delay));
                                                    return fetchWithRetry(url, retries - 1, delay * 2);
                                                }
                                            }

                                            async function loadContent() {
                                                if (isLoading) return;
                                                
                                                // Initial Load: Try Cache first
                                                if (currentPage === (MODE === 'tree' ? 1 : 1)) { // Logic simplification as we reset pages
                                                    try {
                                                        let cachedItems = [];
                                                        if (MODE === 'tree') {
                                                            cachedItems = await CacheManager.getTree(PATH);
                                                        } else {
                                                            cachedItems = await CacheManager.getAll();
                                                        }
                                                        
                                                        if (cachedItems && cachedItems.length > 0) {
                                                            console.log('Rendering from cache:', cachedItems.length);
                                                            const grid = document.getElementById(MODE === 'tree' ? 'tree-grid' : 'video-grid');
                                                            if (grid) {
                                                                const skeletons = grid.querySelectorAll('.skeleton-card');
                                                                skeletons.forEach(s => s.remove());
                                                                
                                                                // If tree mode, we might need to clear existing empty state or previous content if any
                                                                if (MODE === 'tree') grid.innerHTML = ''; 

                                                                renderItems(cachedItems, false); // false = not new
                                                            }
                                                        }
                                                    } catch (e) {
                                                        console.error('Cache load failed', e);
                                                    }
                                                }
                                            
                                                isLoading = true;
                                                const loadingIndicator = document.getElementById('loading-indicator');
                                                if (loadingIndicator) loadingIndicator.classList.remove('hidden');
                                                
                                                try {
                                                    const url = MODE === 'tree' 
                                                        ? `/api/tree?path=` + encodeURIComponent(PATH) + `&page=` + currentPage 
                                                        : `/api/videos?page=` + currentPage;
                                                    
                                                    const response = await fetchWithRetry(url);
                                                    const data = await response.json();
                                                    
                                                    const items = MODE === 'tree' ? data.items : data.videos;
                                                    hasMore = data.hasMore;
                                                    
                                                    // Save to Cache
                                                    if (items.length > 0) {
                                                        if (MODE === 'tree') {
                                                            // For tree, we replace the directory cache for this path (paginated? No, tree API returns page, but usually we want to cache the whole folder if small enough. 
                                                            // BUT my implementation of `saveTree` overwrites. If pagination, we might lose data.
                                                            // Wait, tree API is paginated. If we cache page 1, we save page 1.
                                                            // To simple cache, let's only cache if it's page 1? Or accumulate?
                                                            // Accumulation is hard without state. Let's Cache Page 1 for instant first load.
                                                            if (currentPage === 1) CacheManager.saveTree(PATH, items);
                                                        } else {
                                                            CacheManager.save(items);
                                                        }
                                                    }
                                                    
                                                    // Update Counts
                                                    const countEl = document.getElementById('video-count');
                                                    if (countEl) countEl.textContent = (data.totalItems || data.totalVideos) + (MODE === 'tree' ? ' Items' : ' Videos');
                                                    
                                                    // Clear skeletons
                                                     const grid = document.getElementById(MODE === 'tree' ? 'tree-grid' : 'video-grid');
                                                     if (grid) {
                                                          const skeletons = grid.querySelectorAll('.skeleton-card');
                                                          skeletons.forEach(s => s.remove());
                                                          // For Tree Mode, if we just loaded live data, and we already rendered cache, we should prevent dups.
                                                          // The renderItems has dup check.
                                                     }
                                                    
                                                    renderItems(items, true); // true = live data

                                                    if (items.length > 0) {
                                                         currentPage++;
                                                    }
                                                    
                                                    if (!hasMore && items.length === 0) {
                                                         // Empty state
                                                    }
                                                    
                                                } catch (error) {
                                                    console.error('Failed to load content:', error);
                                                } finally {
                                                    isLoading = false;
                                                    if (loadingIndicator) loadingIndicator.classList.add('hidden');
                                                }
                                            }
                                            
                                            function renderItems(items, isLive) {
                                                const grid = document.getElementById(MODE === 'tree' ? 'tree-grid' : 'video-grid');
                                                if (!grid) return;
                                                
                                                // We need to deduplicate against what's already on screen
                                                // BUT for cache vs live:
                                                // If we rendered cache, 'items' from live might overlap.
                                                // The render logic below checks `document.querySelector` which handles this.
                                                
                                                const fragment = document.createDocumentFragment();
                                                
                                                items.forEach(item => {
                                                    const name = item.name;
                                                    
                                                    // duplicate check
                                                     try {
                                                         if (document.querySelector(`a[href="/${'$'}{name.replace(/"/g, '\\"')}"]`)) return;
                                                     } catch (e) { return; }

                                                    let html = '';
                                                    // ... (HTML Generation Logic extracted or inline)
                                                    // For brevity in this replace, I'll inline the previous logic but streamlined
                                                    
                                                    // Reuse previous logic for MODE check
                                                    if (MODE === 'tree') {
                                                         const type = item.type;
                                                         const size = item.size;
                                                         if (type === 'dir') {
                                                            const newPath = PATH ? (PATH + '/' + name) : name;
                                                            const folderUrl = '/?mode=tree&path=' + encodeURIComponent(newPath);
                                                            html = `<a href="${'$'}{folderUrl}" onclick="handleNavigation(event, '${'$'}{folderUrl}')" class="group block bg-surface-light rounded-2xl p-4 border border-white/5 active:scale-95 transition-transform hover:border-primary/50">
                                                                <div class="flex items-center gap-3 mb-2">
                                                                    <div class="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center text-primary">
                                                                        <span class="material-symbols-rounded">folder</span>
                                                                    </div>
                                                                    <h3 class="font-bold text-sm text-text-main line-clamp-1 break-all">${'$'}{name}</h3>
                                                                </div>
                                                                <div class="flex justify-between items-center text-xs text-text-sub"><span>Folder</span></div>
                                                            </a>`;
                                                         } else {
                                                            const fileSize = size;
                                                            const itemPath = PATH ? (PATH + '/' + name) : name;
                                                            const thumbUrl = '/api/thumbnail/' + encodeURIComponent(name) + '?path=' + encodeURIComponent(itemPath);
                                                            html = createVideoCard(name, fileSize, thumbUrl, isLive);
                                                         }
                                                    } else {
                                                         // Flat Mode
                                                         const fileSize = item.size;
                                                         const thumbUrl = '/api/thumbnail/' + encodeURIComponent(name);
                                                         html = createVideoCard(name, fileSize, thumbUrl, isLive);
                                                    }

                                                    const tempDiv = document.createElement('div');
                                                    tempDiv.innerHTML = html;
                                                    if (tempDiv.firstElementChild) {
                                                        grid.appendChild(tempDiv.firstElementChild);
                                                    }
                                                });
                                                
                                                // Observe new images
                                                if ('IntersectionObserver' in window && window.imageObserver) {
                                                    document.querySelectorAll('img[loading="lazy"]').forEach(img => window.imageObserver.observe(img));
                                                }
                                            }

                                            function createVideoCard(name, size, thumbUrl, isNew = false) {
                                                 const newBadge = isNew ? `<div class="absolute top-2 left-2 bg-primary text-white text-[10px] font-bold px-2 py-0.5 rounded-full shadow-lg z-10">NEW</div>` : '';
                                                 return `<a href="/${'$'}{name}" class="group block bg-surface-light rounded-2xl p-2 border border-white/5 active:scale-95 transition-transform">
                                                     <div class="relative aspect-[4/3] rounded-xl overflow-hidden bg-background mb-3">
                                                         ${'$'}{newBadge}
                                                         <img data-src="${'$'}{thumbUrl}" src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxIDEiPjwvc3ZnPg==" loading="lazy" class="absolute inset-0 w-full h-full object-cover opacity-0 transition-opacity duration-500" onerror="this.classList.add('opacity-0')" />
                                                         <div class="absolute inset-0 flex items-center justify-center -z-10 bg-surface">
                                                             <span class="material-symbols-rounded text-3xl text-text-sub/20 group-hover:text-primary transition-colors">movie</span>
                                                         </div>
                                                         <div class="absolute bottom-2 right-2 w-8 h-8 rounded-full bg-black/60 backdrop-blur flex items-center justify-center">
                                                             <span class="material-symbols-rounded text-white text-lg">play_arrow</span>
                                                         </div>
                                                     </div>
                                                     <div class="px-1 pb-1">
                                                         <h3 class="font-bold text-sm text-text-main line-clamp-2 leading-snug mb-1">${'$'}{name}</h3>
                                                         <p class="text-[11px] text-text-sub">${'$'}{size} MB</p>
                                                     </div>
                                                </a>`;
                                            }
                                            
                                            // Intersection Observer
                                            if ('IntersectionObserver' in window) {
                                                const sentinelObserver = new IntersectionObserver((entries) => {
                                                    if (entries[0].isIntersecting && hasMore) {
                                                        loadContent();
                                                    }
                                                }, { rootMargin: '200px' });
                                                
                                                const sentinel = document.getElementById('loading-sentinel');
                                                if (sentinel) sentinelObserver.observe(sentinel);
                                            }
                                            
                                            // Connection Monitoring
                                            function updateConnectionStatus(online) {
                                                const overlay = document.getElementById('connection-lost');
                                                if (online) {
                                                    if (overlay) overlay.classList.remove('visible');
                                                    // Retry loading if we were offline
                                                    if (!isLoading && hasMore) loadContent();
                                                    
                                                    // Reload page if we were stuck in initial loading
                                                    if (document.getElementById('video-count') && document.getElementById('video-count').textContent === 'Loading...') {
                                                        window.location.reload();
                                                    }
                                                } else {
                                                    if (overlay) overlay.classList.add('visible');
                                                }
                                            }
                                            
                                            window.addEventListener('online', () => updateConnectionStatus(true));
                                            window.addEventListener('offline', () => updateConnectionStatus(false));
                                            
                                            // Flat mode initial load
                                            if (MODE === 'flat') {
                                                 setTimeout(() => loadContent(), 100);
                                            }
                                        </script>
                                        </section>""")
                                    }
                                } // End withContext

                                // --- FOOTER ---
                                writer.write("""
    </main>
    
    <!-- Bottom Navigation -->
    <nav class="fixed bottom-0 left-0 right-0 bg-background/95 backdrop-blur-xl border-t border-white/5 px-6 py-2 pb-5 z-50">
        <div class="flex items-center justify-between max-w-sm mx-auto">
            <a href="/?theme=$themeParam" class="flex flex-col items-center gap-1 ${if (mode != "tree") "text-primary" else "text-text-sub"}">
                <span class="material-symbols-rounded text-2xl">home</span>
                <span class="text-[10px] font-bold">Home</span>
            </a>
            
            <a href="/?mode=tree&theme=$themeParam" class="w-14 h-14 -mt-8 rounded-full flex items-center justify-center shadow-[0_0_20px_${if (mode == "tree") "rgba(250,198,56,0.3)" else "rgba(0,0,0,0)"}] border-4 border-background ${if (mode == "tree") "bg-primary text-black" else "bg-surface-light text-text-sub"} active:scale-90 transition-all">
                <span class="material-symbols-rounded text-3xl">folder</span>
            </a>
            
            <a href="http://settings" class="flex flex-col items-center gap-1 text-text-sub hover:text-text-main transition-colors">
                <span class="material-symbols-rounded text-2xl">settings</span>
                <span class="text-[10px] font-medium">Settings</span>
            </a>
        </div>
    </nav>
</body>
</html>""")
                                writer.flush() 
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
                                 val cachedBytes = ThumbnailMemoryCache.get(memCacheKey)
                                 
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
                                        val rootDir = DocumentFile.fromTreeUri(appContext, treeUri)
                                        if (rootDir != null) {
                                            var current = rootDir
                                            val parts = path.split("/").filter { it.isNotEmpty() }
                                            for (part in parts) {
                                                val children = getDirectoryListing(current!!)
                                                val next = children.find { it.name == part }
                                                if (next != null) current = next else { current = null as DocumentFile?; break }
                                            }
                                            targetFile = current
                                        }
                                    } catch (e: Exception) { e.printStackTrace() }
                                } 
                                
                                if (targetFile == null) targetFile = cachedFilesMap[filename]
                                 
                                if (targetFile != null) {
                                    val thumbnail = generateThumbnail(targetFile.uri, filename)
                                    if (thumbnail != null) {
                                        // Update Memory Cache
                                        ThumbnailMemoryCache.put(memCacheKey, thumbnail)
                                        
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
                                val targetFile = cachedFilesMap[filename]
                                
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
                                            val inputStream = appContext.contentResolver.openInputStream(targetFile.uri) ?: throw Exception("Cannot open stream")
                                            val trackingStream = TrackingInputStream(inputStream)
                                            return trackingStream.toByteReadChannel()
                                        }
                                    })
                                } else {
                                    call.respond(HttpStatusCode.NotFound)
                                }
                            }
                        }
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
     */
    suspend fun refreshCache() {
        withContext(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@withContext
            
            val includeSubfolders = com.thiyagu.media_server.data.UserPreferences(appContext).subfolderScanningFlow.first()
            
            // Clear existing caches
             // Note: For progressive reload, we might want to keep old ones until we find new ones? 
             // But simpler to clear start fresh or else we get duplicates easily.
             // If we clear, the UI will empty out until scan finds them.
             
            // To be safe and clean:
            cachedFilesMap.clear()
            allVideoFiles.clear()
            scannedCount.set(0)
            isScanning.set(true)
            _scanStatus.value = ScanStatus(true, 0)
            
            flatCacheTimestamp = System.currentTimeMillis()

            scope.launch(Dispatchers.IO) {
                try {
                    if (includeSubfolders) {
                        scanRecursivelyAsync(dir)
                    } else {
                        val files = dir.listFiles().filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm") }
                        files.forEach { file ->
                            file.name?.let { name ->
                                cachedFilesMap[name] = file
                                allVideoFiles.add(file)
                            }
                        }
                        scannedCount.set(files.size)
                    }
                } finally {
                    isScanning.set(false)
                    _scanStatus.value = ScanStatus(false, scannedCount.get())
                }
            }
        }
    }
    
    /**
     * Recursive scan that updates collections progressively.
     */
    private suspend fun scanRecursivelyAsync(root: DocumentFile) {
        val stack = java.util.ArrayDeque<DocumentFile>()
        stack.push(root)

        while (stack.isNotEmpty()) {
            // Check for cancellation
             if (!kotlinx.coroutines.currentCoroutineContext().isActive) break

            val current = stack.pop()
            val children = current.listFiles() // Blocking IO
            
            for (child in children) {
                if (child.isDirectory) {
                    stack.push(child)
                } else {
                    if (child.name?.substringAfterLast('.', "")?.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm")) {
                         child.name?.let { name ->
                             // Thread-safe additions
                             if (cachedFilesMap.putIfAbsent(name, child) == null) {
                                 // Only add if not already present (first win)
                                 allVideoFiles.add(child)
                                 val newCount = scannedCount.incrementAndGet()
                                 
                                 // Emit updates periodically (every 10 items) or if it's the first few
                                 if (newCount % 10 == 0 || newCount < 10) {
                                     _scanStatus.value = ScanStatus(true, newCount)
                                 }
                             }
                         }
                    }
                }
            }
        }
        // Final update
        _scanStatus.value = ScanStatus(false, scannedCount.get())
    }

    /**
     * Stops the running server gracefully.
     * Waits up to 1000ms for existing calls to finish, then hard stops after 5000ms.
     */
    fun stop() {
        server?.stop(1000, 5000)
        scope.cancel()
    }
    
    // Refresh cache only if needed (time-based)
    suspend fun refreshCacheIfNeeded() {
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

    private class TrackingInputStream(
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
    private fun getDirectoryListing(dir: DocumentFile): List<DocumentFile> {
        val uriKey = dir.uri.toString()
        val now = System.currentTimeMillis()
        
        val cached = directoryCache[uriKey]
        if (cached != null && (now - cached.timestamp < CACHE_DURATION_MS)) {
            return cached.files
        }
        
        // Scan
        val files = dir.listFiles().toList()
        directoryCache[uriKey] = CachedDirectory(files, now)
        return files
    }
}
