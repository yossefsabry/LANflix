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
                        // ... Routing Logic (Lines 34-202) ...
                        get("/") {
                            val dir = DocumentFile.fromTreeUri(appContext, treeUri)
                            if (dir == null || !dir.isDirectory) {
                                call.respondText("Directory not found or accessible", status = HttpStatusCode.NotFound)
                                return@get
                            }
    
                            val visibilityManager = com.thiyagu.media_server.utils.VideoVisibilityManager(appContext)
    
                            val files = dir.listFiles().filter { 
                                !it.isDirectory && it.name != null && 
                                (it.name!!.endsWith(".mp4", true) || it.name!!.endsWith(".mkv", true) || it.name!!.endsWith(".avi", true) || it.name!!.endsWith(".mov", true) || it.name!!.endsWith(".webm", true)) &&
                                !visibilityManager.isVideoHidden(it.name!!)
                            }
    
                            val html = StringBuilder()
                            html.append("""
    <!DOCTYPE html>
    <html lang="en" class="dark">
    <head>
        <meta charset="utf-8"/>
        <meta content="width=device-width, initial-scale=1.0, viewport-fit=cover" name="viewport"/>
        <meta content="#0a0a0a" name="theme-color"/>
        <title>LANflix - Browse</title>
        <link href="https://fonts.googleapis.com/css2?family=Spline+Sans:wght@300;400;500;600;700&display=swap" rel="stylesheet"/>
        <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:wght,FILL@100..700,0..1&display=swap" rel="stylesheet"/>
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
                            display: ['Spline Sans', 'ui-sans-serif', 'system-ui', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'Helvetica Neue', 'Arial', 'sans-serif'] 
                        }
                    }
                }
            };
        </script>
        <style>
            body { 
                font-family: 'Spline Sans', ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; 
                -webkit-tap-highlight-color: transparent; 
                -webkit-font-smoothing: antialiased;
                -moz-osx-font-smoothing: grayscale;
            }
            .line-clamp-2 { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
        </style>
    </head>
    <body class="bg-background text-text-main min-h-screen pb-10 selection:bg-primary selection:text-black">
        
        <!-- Header -->
        <header class="sticky top-0 z-50 bg-background/90 backdrop-blur-md px-4 py-4 border-b border-white/5">
            <div class="flex items-center justify-between max-w-4xl mx-auto">
                <h1 class="text-2xl font-bold tracking-tight text-text-main flex items-center gap-2">
                    <span class="material-symbols-outlined text-primary" style="font-size: 32px;">play_circle</span>
                    LANflix
                </h1>
                <div class="w-10 h-10 rounded-full bg-surface-light border border-white/5 flex items-center justify-center">
                     <span class="material-symbols-outlined text-text-sub">person</span>
                </div>
            </div>
        </header>
    
        <main class="max-w-4xl mx-auto px-4 pt-6">
            <div class="flex items-center justify-between mb-6">
                 <h2 class="text-xl font-bold text-text-main">Library</h2>
                 <span class="text-sm text-text-sub font-medium">${files.size} Videos</span>
            </div>
    
            <!-- Video Grid -->
            <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
                            """)
    
                            for (file in files) {
                                val fileSizeMb = file.length() / (1024 * 1024)
                                val name = file.name ?: "Unknown"
                                
                                html.append("""
                <a href="/${name}" class="group block">
                    <div class="relative aspect-video rounded-xl overflow-hidden bg-surface-light border border-white/5 mb-3 group-hover:border-primary/50 transition-colors">
                        <!-- Placeholder Thumbnail -->
                        <div class="w-full h-full flex items-center justify-center bg-white/5">
                            <span class="material-symbols-outlined text-4xl text-text-sub/30 group-hover:text-primary transition-colors duration-300">movie</span>
                        </div>
                        
                        <!-- Play Overlay -->
                        <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center">
                            <div class="w-10 h-10 bg-primary rounded-full flex items-center justify-center shadow-lg transform scale-90 group-hover:scale-100 transition-transform">
                                 <span class="material-symbols-outlined text-black">play_arrow</span>
                            </div>
                        </div>
                    </div>
                    
                    <div>
                        <h3 class="font-medium text-[15px] leading-tight text-text-main line-clamp-2 group-hover:text-primary transition-colors">${name}</h3>
                        <p class="text-xs text-text-sub mt-1 font-medium">${fileSizeMb} MB</p>
                    </div>
                </a>
                                """)
                            }
    
                            if (files.isEmpty()) {
                                html.append("""
                                    <div class="col-span-full flex flex-col items-center justify-center py-20 text-text-sub opactiy-60">
                                        <span class="material-symbols-outlined text-6xl mb-4 opacity-20">folder_off</span>
                                        <p class="text-lg">No videos found</p>
                                        <p class="text-sm mt-2 opacity-60">Add .mp4, .mkv, .avi files to the selected folder</p>
                                    </div>
                                """)
                            }
    
                            html.append("""
            </div>
        </main>
        
        <footer class="mt-12 text-center pb-8">
            <p class="text-xs text-text-sub/40 font-bold tracking-widest uppercase">Powered by LANflix Server</p>
        </footer>
    
    </body>
    </html>
                            """)
                            
                            call.respondText(html.toString(), ContentType.Text.Html)
                        }
    
                        get("/{filename}") {
                            val filename = call.parameters["filename"]
                            if (filename != null) {
                                val dir = DocumentFile.fromTreeUri(appContext, treeUri)
                                val targetFile = dir?.listFiles()?.find { it.name == filename }
                                
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
}
