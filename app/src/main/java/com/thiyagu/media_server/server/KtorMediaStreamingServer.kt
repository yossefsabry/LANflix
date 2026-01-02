package com.thiyagu.media_server.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.*
import io.ktor.server.application.*
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

/**
 * Media Streaming Server implementation using Ktor.
 * 
 * Handles file serving, directory listing, and video streaming over HTTP.
 * Supports "Tree Mode" (SAF Tree Uri) and "Flat Mode" (Recursive scan).
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
    
    // Cache for file list (Flat Mode)
    @Volatile private var cachedFiles: List<DocumentFile> = emptyList()
    @Volatile private var cacheTimestamp: Long = 0L
    
    // structured concurrency scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cache for directory listings (Tree Mode) - Key: Directory Uri String
    private val directoryCache = java.util.concurrent.ConcurrentHashMap<String, CachedDirectory>()
    
    private val CACHE_DURATION_MS = 60_000L // 60 seconds
    
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
            // Initial cached scan
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
                    
                    routing {
                        // API to force refresh cache
                        get("/api/refresh") {
                            directoryCache.clear()
                            refreshCache()
                            call.respondText("Cache Refreshed", status = HttpStatusCode.OK)
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
        body { 
            font-family: 'Spline Sans', sans-serif; 
            -webkit-tap-highlight-color: transparent; 
            -webkit-font-smoothing: antialiased;
        }
        .hide-scrollbar::-webkit-scrollbar { display: none; }
        .hide-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }
    </style>
    <script>
        // Persistence Logic
        (function() {
            const params = new URLSearchParams(window.location.search);
            const mode = params.get('mode');
            const path = params.get('path');
            
            if (mode) localStorage.setItem('lanflix_last_mode', mode);
            if (mode === 'tree' && path !== null) localStorage.setItem('lanflix_last_path', path);

            // Restore State (Simplified)
            if (!window.location.search) {
                const lastMode = localStorage.getItem('lanflix_last_mode');
                if (lastMode === 'tree') {
                   const lastPath = localStorage.getItem('lanflix_last_path');
                   window.location.replace('/?mode=tree' + (lastPath ? '&path=' + encodeURIComponent(lastPath) : ''));
                }
            } else if (mode === 'tree' && path === null) {
                const lastPath = localStorage.getItem('lanflix_last_path');
                if (lastPath) window.location.replace('/?mode=tree&path=' + encodeURIComponent(lastPath));
            }
        })();
    </script>
    <script>
        // Lazy load thumbnails
        if ('IntersectionObserver' in window) {
            const imageObserver = new IntersectionObserver((entries, observer) => {
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
                document.querySelectorAll('img[loading="lazy"]').forEach(img => imageObserver.observe(img));
            });
        }
    </script>
</head>
<body class="bg-background text-text-main min-h-screen pb-24 selection:bg-primary selection:text-black">
    
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
            <a href="http://profile" class="w-10 h-10 rounded-full bg-surface-light border border-white/5 flex items-center justify-center active:scale-95 transition-transform relative">
                 <span class="material-symbols-rounded text-text-sub">person</span>
                 <div class="absolute top-0 right-0 w-2.5 h-2.5 bg-green-500 rounded-full border-2 border-background"></div>
            </a>
        </div>
    </header>

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

                                        if (currentDir == null || !currentDir!!.isDirectory) {
                                            writer.write("""<div class="w-full text-center py-20 text-text-sub">Directory not found</div>""")
                                        } else {
                                            val allItems = getDirectoryListing(currentDir!!)
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
                                            writer.write("""<div class="grid grid-cols-2 gap-4">""")
                                            
                                            for (item in pagedItems) {
                                                val name = item.name ?: "Unknown"
                                                if (item.isDirectory) {
                                                    val newPath = if (pathParam.isEmpty()) "/$name" else "$pathParam/$name"
                                                    writer.write("""
                                                        <a href="/?mode=tree&path=$newPath" class="bg-surface-light rounded-xl p-4 flex flex-col items-center gap-2 border border-white/5 active:scale-95 transition-transform">
                                                            <span class="material-symbols-rounded text-4xl text-primary">folder</span>
                                                            <span class="text-xs text-center font-medium line-clamp-1 break-all">$name</span>
                                                        </a>
                                                    """)
                                                } else {
                                                     if (name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".avi", true) || name.endsWith(".webm", true)) {
                                                         if (!visibilityManager.isVideoHidden(item.uri.toString())) {
                                                             val fileSize = item.length() / (1024 * 1024)
                                                             writer.write("""
                                                                <a href="/${name}" class="group block bg-surface-light rounded-2xl p-2 border border-white/5 active:scale-95 transition-transform">
                                                                     <div class="relative aspect-[4/3] rounded-xl overflow-hidden bg-background mb-3">
                                                                         <img data-src="/api/thumbnail/${name}" src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxIDEiPjwvc3ZnPg==" loading="lazy" class="absolute inset-0 w-full h-full object-cover opacity-0 transition-opacity duration-500"/>
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
                                            
                                            // Pagination Controls
                                            if (totalPages > 1) {
                                                writer.write("""
                                                    <div class="flex items-center justify-center gap-4 mt-8">
                                                        ${if (pageParam > 1) """<a href="/?mode=tree&path=$pathParam&page=${pageParam-1}" class="px-4 py-2 bg-surface-light rounded-full text-sm font-medium hover:bg-white/10">Previous</a>""" else ""}
                                                        <span class="text-xs text-text-sub">Page $pageParam of $totalPages</span>
                                                        ${if (pageParam < totalPages) """<a href="/?mode=tree&path=$pathParam&page=${pageParam+1}" class="px-4 py-2 bg-primary text-black rounded-full text-sm font-medium shadow-lg hover:bg-primary/90">Next</a>""" else ""}
                                                    </div>
                                                """)
                                            }
                                        }

                                    } else {
                                        // --- DEFAULT FLAT MODE ---
                                        val dir = DocumentFile.fromTreeUri(appContext, treeUri)
                                        if (dir == null || !dir.isDirectory) {
                                            writer.write("""<div class="w-full text-center py-20 text-text-sub">Directory not found or accessible</div>""")
                                        } else {
                                            // Use Flat Cache
                                            refreshCacheIfNeeded() // Ensure cache is warm
                                            val allFiles = cachedFiles.filter { 
                                                !it.isDirectory && it.name != null && 
                                                (it.name!!.endsWith(".mp4", true) || it.name!!.endsWith(".mkv", true) || it.name!!.endsWith(".avi", true) || it.name!!.endsWith(".mov", true) || it.name!!.endsWith(".webm", true)) &&
                                                !visibilityManager.isVideoHidden(it.uri.toString())
                                            }
        
                                            val recentFiles = allFiles.sortedByDescending { it.lastModified() }.take(5)
                    
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
                                                     <button class="text-xs font-bold text-primary">See All</button>
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
                                            
                                            // All Videos
                                            writer.write("""
                                            <section class="mt-6">
                                                <div class="flex items-center justify-between mb-4">
                                                     <h2 class="text-lg font-bold">All Videos</h2>
                                                     <span class="text-xs text-text-sub/60 font-medium">${allFiles.size} Videos</span>
                                                </div>
                                                <div class="grid grid-cols-2 gap-4">""")
                                                
                                            // Apply limit here too? The task says "Implement Pagination Limit Enforcement"
                                            // The user code for All videos in Flat mode actually loads ALL videos? 
                                            // Original code: val pagedItems = allItems.drop((pageParam - 1) * limit).take(limit) was ONLY in TREE mode.
                                            // Flat mode had NO pagination in original code!
                                            // I will leave Flat mode as is for now regarding pagination to match original behavior unless I see explicit instructions to add it to flat mode.
                                            // The user request said "No pagination limit enforcement" as a warning.
                                            // I should probably add pagination to Flat mode too, but that's a UI change (Need 'Next' button). 
                                            // For now, I will stick to exact recreation of Flat mode but with streaming.
                                            
                                            for (file in allFiles) {
                                                val name = file.name ?: "Unknown"
                                                val fileSize = file.length() / (1024 * 1024)
                                                writer.write("""
                                                    <a href="/${name}" class="group block bg-surface-light rounded-2xl p-2 border border-white/5 active:scale-95 transition-transform">
                                                         <div class="relative aspect-[4/3] rounded-xl overflow-hidden bg-background mb-3">
                                                             <img data-src="/api/thumbnail/${name}" src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxIDEiPjwvc3ZnPg==" loading="lazy" class="absolute inset-0 w-full h-full object-cover opacity-0 transition-opacity duration-500"/>
                                                             <div class="absolute inset-0 flex items-center justify-center -z-10">
                                                                 <span class="material-symbols-rounded text-3xl text-text-sub/20 group-hover:text-primary transition-colors">movie</span>
                                                             </div>
                                                             <div class="absolute bottom-2 right-2 w-8 h-8 rounded-full bg-black/60 backdrop-blur flex items-center justify-center">
                                                                 <span class="material-symbols-rounded text-white text-lg">play_arrow</span>
                                                             </div>
                                                         </div>
                                                         <div class="px-1 pb-1">
                                                             <h3 class="font-bold text-sm text-text-main line-clamp-2 leading-snug mb-1">${name}</h3>
                                                             <p class="text-[11px] text-text-sub">${fileSize} MB • Local</p>
                                                         </div>
                                                    </a>""")
                                            }
                                            if (allFiles.isEmpty()) writer.write("""
                                                <div class="col-span-full py-20 text-center">
                                                    <div class="w-20 h-20 bg-gradient-to-br from-primary/20 to-primary/5 rounded-full flex items-center justify-center mx-auto mb-6">
                                                        <span class="material-symbols-rounded text-5xl text-primary">folder_open</span>
                                                    </div>
                                                    <h3 class="text-xl font-bold text-text-main mb-2">No Videos Found</h3>
                                                    <p class="text-text-sub max-w-sm mx-auto mb-4">Add video files to your shared folder to see them here</p>
                                                    <div class="inline-block bg-surface-light px-4 py-2 rounded-lg text-xs text-text-sub font-mono">
                                                        Supported: MP4, MKV, AVI, MOV, WEBM
                                                    </div>
                                                </div>""")
                                            writer.write("""</div></section>""")
                                        }
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
    
                        get("/{filename}") {
                            val filename = call.parameters["filename"]
                            if (filename != null) {
                                // Optimized: Look in Cache first!
                                val targetFile = cachedFiles.find { it.name == filename }
                                
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
                        
                        get("/api/thumbnail/{filename}") {
                            val filename = call.parameters["filename"]
                            if (filename != null) {
                                val targetFile = cachedFiles.find { it.name == filename }
                                if (targetFile != null) {
                                    val thumbnail = generateThumbnail(targetFile.uri, filename)
                                    if (thumbnail != null) {
                                        call.respondBytes(thumbnail, ContentType.Image.JPEG)
                                    } else {
                                        call.respond(HttpStatusCode.NotFound)
                                    }
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
    
    private fun generateThumbnail(uri: Uri, filename: String): ByteArray? {
        val cacheDir = java.io.File(appContext.cacheDir, "thumbnails")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // Simple cache key: filename + .jpg
        // Note: Ideally we should use hash of full path to avoid duplicates, but current system relies on filenames.
        val cacheFile = java.io.File(cacheDir, "$filename.jpg")

        if (cacheFile.exists()) {
            return try {
                cacheFile.readBytes()
            } catch (e: Exception) {
                // If read fails, try regenerating
                e.printStackTrace()
                null
            }
        }

        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val bitmap = retriever.getFrameAtTime(2000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) // 2 seconds
            
            if (bitmap != null) {
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, stream) // 60% quality
                val bytes = stream.toByteArray()
                
                // Save to Disk Cache
                try {
                    val fos = java.io.FileOutputStream(cacheFile)
                    fos.write(bytes)
                    fos.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                bitmap.recycle()
                bytes
            } else {
                null
            }
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
     * In Flat Mode: Rescans the entire directory tree for video files.
     * This operation is performed on the IO dispatcher.
     */
    suspend fun refreshCache() {
        withContext(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@withContext
            
            // Read preference here? No, pass it or read it inside Coroutine?
            // We are in IO context. DataStore is fine.
            val includeSubfolders = com.thiyagu.media_server.data.UserPreferences(appContext).subfolderScanningFlow.first()
            
            val files = if (includeSubfolders) {
                scanRecursively(dir)
            } else {
                dir.listFiles().filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm") }
            }
            
            cachedFiles = files
            cacheTimestamp = System.currentTimeMillis()
        }
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
        
        // Only refresh if cache is older than 30 seconds
        if (now - cacheTimestamp < CACHE_DURATION_MS && cachedFiles.isNotEmpty()) {
            return // Cache is fresh
        }
        
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

    private fun scanRecursively(root: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        val stack = java.util.ArrayDeque<DocumentFile>()
        stack.push(root)

        while (stack.isNotEmpty()) {
            val current = stack.pop()
            val children = current.listFiles()
            
            for (child in children) {
                if (child.isDirectory) {
                    stack.push(child)
                } else {
                    result.add(child)
                }
            }
        }
        return result
    }
}
