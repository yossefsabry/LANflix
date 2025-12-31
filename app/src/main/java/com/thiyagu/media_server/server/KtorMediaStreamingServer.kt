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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class KtorMediaStreamingServer(
    private val appContext: Context,
    private val treeUri: Uri,
    private val port: Int
) {
    private var server: ApplicationEngine? = null


    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                server = embeddedServer(Netty, port = port) {
                    install(PartialContent)
                    install(AutoHeadResponse)
                    
                    routing {
                        get("/") {
                            val dir = DocumentFile.fromTreeUri(appContext, treeUri)
                            if (dir == null || !dir.isDirectory) {
                                call.respondText("Directory not found or accessible", status = HttpStatusCode.NotFound)
                                return@get
                            }
    
                            // Read Preference
                            val includeSubfolders = com.thiyagu.media_server.data.UserPreferences(appContext).subfolderScanningFlow.first()
                            val visibilityManager = com.thiyagu.media_server.utils.VideoVisibilityManager(appContext)
    
                            // Get all video files
                            val allFilesRaw = if (includeSubfolders) {
                                scanRecursively(dir)
                            } else {
                                dir.listFiles().toList()
                            }
                            
                            val allFiles = allFilesRaw.filter { 
                                !it.isDirectory && it.name != null && 
                                (it.name!!.endsWith(".mp4", true) || it.name!!.endsWith(".mkv", true) || it.name!!.endsWith(".avi", true) || it.name!!.endsWith(".mov", true) || it.name!!.endsWith(".webm", true)) &&
                                !visibilityManager.isVideoHidden(it.uri.toString())
                            }

                            // Sort by last modified for "Recently Added"
                            val recentFiles = allFiles.sortedByDescending { it.lastModified() }.take(5)
    
                            val html = StringBuilder()
                            html.append("""
    <!DOCTYPE html>
    <html lang="en" class="dark">
    <head>
        <meta charset="utf-8"/>
        <meta content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover" name="viewport"/>
        <meta content="#0a0a0a" name="theme-color"/>
        <title>LANflix</title>
        <link href="https://fonts.googleapis.com/css2?family=Spline+Sans:wght@300;400;500;600;700&display=swap" rel="stylesheet"/>
        <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0,0" rel="stylesheet"/>
        <script src="https://cdn.tailwindcss.com?plugins=forms,container-queries"></script>
        <script>
            tailwind.config = {
                darkMode: 'class',
                theme: {
                    extend: {
                        colors: {
                            primary: "#FAC638",
                            background: "#0a0a0a",
                            surface: "#171717",
                            "surface-light": "#262626",
                            "text-main": "#f2f2f2",
                            "text-sub": "#9ca3af"
                        },
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
    </head>
    <body class="bg-background text-text-main min-h-screen pb-24 selection:bg-primary selection:text-black">
        
        <!-- Sticky Header -->
        <header class="sticky top-0 z-50 bg-background/95 backdrop-blur-md px-4 py-3 border-b border-white/5">
            <div class="flex items-center justify-between">
                <!-- Exit Button (Left) -->
                <a href="http://exit" class="w-10 h-10 rounded-full bg-surface-light border border-white/5 flex items-center justify-center active:scale-95 transition-transform">
                     <span class="material-symbols-rounded text-text-sub">arrow_back</span>
                </a>

                <!-- Logo (Center) -->
                <div class="flex items-center gap-2">
                    <span class="material-symbols-rounded text-primary text-2xl">play_circle</span>
                    <h1 class="text-xl font-bold tracking-tight">LANflix</h1>
                </div>

                <!-- Profile Button (Right) -->
                <a href="http://profile" class="w-10 h-10 rounded-full bg-surface-light border border-white/5 flex items-center justify-center active:scale-95 transition-transform relative">
                     <span class="material-symbols-rounded text-text-sub">person</span>
                     <div class="absolute top-0 right-0 w-2.5 h-2.5 bg-green-500 rounded-full border-2 border-background"></div>
                </a>
            </div>
        </header>
    
        <main class="px-4 py-4 space-y-6">
            
            <!-- Search Bar -->
            <div class="relative">
                <span class="material-symbols-rounded absolute left-4 top-1/2 -translate-y-1/2 text-text-sub">search</span>
                <input type="text" placeholder="Search local videos..." 
                    class="w-full bg-surface-light border border-white/5 rounded-full py-3.5 pl-12 pr-12 text-sm placeholder:text-text-sub/50 focus:ring-1 focus:ring-primary focus:border-primary/50 transition-all outline-none text-text-main">
                <button class="absolute right-2 top-1/2 -translate-y-1/2 p-2 rounded-full hover:bg-white/5 text-text-sub">
                    <span class="material-symbols-rounded text-[20px]">tune</span>
                </button>
            </div>

            <!-- Recently Added -->
            <section>
                <div class="flex items-center justify-between mb-4">
                     <h2 class="text-lg font-bold">Recently Added</h2>
                     <button class="text-xs font-bold text-primary">See All</button>
                </div>
                
                <div class="flex gap-4 overflow-x-auto hide-scrollbar -mx-4 px-4 scroll-pl-4 snap-x">
                            """)

                            for (file in recentFiles) {
                                val name = file.name ?: "Unknown"
                                val fileSize = file.length() / (1024 * 1024)
                                
                                html.append("""
                    <a href="/${name}" class="flex-none w-64 snap-start group relative rounded-2xl overflow-hidden aspect-video bg-surface-light border border-white/5">
                        <!-- Play Overlay -->
                        <div class="absolute inset-0 bg-black/20 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity z-10">
                            <div class="w-12 h-12 bg-white/20 backdrop-blur-sm rounded-full flex items-center justify-center">
                                <span class="material-symbols-rounded text-white text-3xl">play_arrow</span>
                            </div>
                        </div>
                        
                        <div class="absolute inset-0 flex items-center justify-center bg-white/5">
                            <span class="material-symbols-rounded text-4xl text-text-sub/20">movie</span>
                        </div>
                        
                        <!-- Gradient Overlay -->
                        <div class="absolute inset-0 bg-gradient-to-t from-black/90 via-black/20 to-transparent"></div>
                        
                        <div class="absolute bottom-3 left-3 right-3">
                            <h3 class="font-bold text-sm text-white line-clamp-1 mb-1">${name}</h3>
                            <div class="flex items-center gap-2 text-[10px] text-gray-300 font-medium">
                                <span class="bg-primary/20 text-primary px-1.5 py-0.5 rounded">NEW</span>
                                <span>${fileSize} MB</span>
                            </div>
                        </div>
                    </a>
                                """)
                            }
                            
                            if (recentFiles.isEmpty()) {
                                html.append("""<div class="w-full text-center py-8 text-text-sub text-sm">No recent videos</div>""")
                            }

                            html.append("""
                </div>
            </section>

            <!-- All Videos -->
            <section>
                <div class="flex items-center justify-between mb-4">
                     <h2 class="text-lg font-bold">All Videos</h2>
                     <span class="text-xs text-text-sub/60 font-medium">${allFiles.size} Videos</span>
                </div>

                <div class="grid grid-cols-2 gap-4">
                            """)

                            for (file in allFiles) {
                                val name = file.name ?: "Unknown"
                                val fileSize = file.length() / (1024 * 1024)
                                
                                html.append("""
                    <a href="/${name}" class="group block bg-surface-light rounded-2xl p-2 border border-white/5 active:scale-95 transition-transform">
                         <div class="relative aspect-[4/3] rounded-xl overflow-hidden bg-background mb-3">
                             <div class="absolute inset-0 flex items-center justify-center">
                                 <span class="material-symbols-rounded text-3xl text-text-sub/20 group-hover:text-primary transition-colors">movie</span>
                             </div>
                             
                             <!-- Play Icon -->
                             <div class="absolute bottom-2 right-2 w-8 h-8 rounded-full bg-black/60 backdrop-blur flex items-center justify-center">
                                 <span class="material-symbols-rounded text-white text-lg">play_arrow</span>
                             </div>
                         </div>
                         
                         <div class="px-1 pb-1">
                             <h3 class="font-bold text-sm text-text-main line-clamp-2 leading-snug mb-1">${name}</h3>
                             <p class="text-[11px] text-text-sub">${fileSize} MB • Local</p>
                         </div>
                    </a>
                                """)
                            }
                            
                            if (allFiles.isEmpty()) {
                                html.append("""
                                    <div class="col-span-full py-20 text-center">
                                        <div class="w-16 h-16 bg-surface-light rounded-full flex items-center justify-center mx-auto mb-4">
                                            <span class="material-symbols-rounded text-3xl text-text-sub">videocam_off</span>
                                        </div>
                                        <p class="text-text-sub">No videos found in this folder.</p>
                                    </div>
                                """)
                            }

                            html.append("""
                </div>
            </section>
        </main>
        
        <!-- Bottom Navigation -->
        <nav class="fixed bottom-0 left-0 right-0 bg-background/95 backdrop-blur-xl border-t border-white/5 px-6 py-2 pb-5 z-50">
            <div class="flex items-center justify-between max-w-sm mx-auto">
                <button class="flex flex-col items-center gap-1 text-primary">
                    <span class="material-symbols-rounded text-2xl">grid_view</span>
                    <span class="text-[10px] font-bold">Browse</span>
                </button>
                
                <!-- Floating Action Button -->
                <button class="w-14 h-14 -mt-8 bg-primary rounded-full flex items-center justify-center shadow-[0_0_20px_rgba(250,198,56,0.3)] border-4 border-background text-black active:scale-90 transition-transform">
                    <span class="material-symbols-rounded text-3xl">play_arrow</span>
                </button>
                
                <button class="flex flex-col items-center gap-1 text-text-sub hover:text-text-main transition-colors">
                    <span class="material-symbols-rounded text-2xl">settings</span>
                    <span class="text-[10px] font-medium">Settings</span>
                </button>
            </div>
        </nav>
    
    </body>
    </html>
                            """)
                            
                            call.respondText(html.toString(), ContentType.Text.Html)
                        }
    
                        get("/{filename}") {
                            val filename = call.parameters["filename"]
                            if (filename != null) {
                                val dir = DocumentFile.fromTreeUri(appContext, treeUri)
                                if (dir == null) {
                                    call.respond(HttpStatusCode.NotFound)
                                    return@get
                                }
                                
                                val includeSubfolders = com.thiyagu.media_server.data.UserPreferences(appContext).subfolderScanningFlow.first()
                                
                                val targetFile = if (includeSubfolders) {
                                    findFileRecursively(dir, filename)
                                } else {
                                    dir.listFiles().find { it.name == filename }
                                }
                                
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

    fun stop() {
        server?.stop(1000, 2000)
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
    
    private fun findFileRecursively(root: DocumentFile, targetName: String): DocumentFile? {
        val stack = java.util.ArrayDeque<DocumentFile>()
        stack.push(root)

        while (stack.isNotEmpty()) {
            val current = stack.pop()
            val children = current.listFiles()
            
            for (child in children) {
                if (child.isDirectory) {
                    stack.push(child)
                } else {
                    if (child.name == targetName) {
                        return child
                    }
                }
            }
        }
        return null
    }
}
