package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import com.thiyagu.media_server.server.pages.DiagnosticsPageTemplate
import com.thiyagu.media_server.server.pages.HomePageTemplate
import com.thiyagu.media_server.server.pages.LoginPageTemplate
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.BufferedWriter
import java.io.OutputStreamWriter

internal fun Routing.registerWebRoutes(server: KtorMediaStreamingServer) {
    get("/api/icon/{size}") {
        val size = call.parameters["size"]?.toIntOrNull() ?: 192
        try {
            val resources = server.appContext.resources
            val resId = resources.getIdentifier("ic_launcher", "mipmap", server.appContext.packageName)
            if (resId == 0) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val drawable = androidx.core.content.ContextCompat.getDrawable(server.appContext, resId)
            if (drawable == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            call.respondOutputStream(ContentType.Image.PNG) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    get("/static/{path...}") {
        val path = call.parameters.getAll("path")?.joinToString("/") ?: return@get
        try {
            val assetPath = "web/$path"
            val inputStream = server.appContext.assets.open(assetPath)
            val contentType = when {
                path.endsWith(".css", ignoreCase = true) -> ContentType.Text.CSS
                path.endsWith(".js", ignoreCase = true) -> ContentType.Application.JavaScript
                path.endsWith(".ttf", ignoreCase = true) -> ContentType.parse("font/ttf")
                path.endsWith(".woff2", ignoreCase = true) -> ContentType.parse("font/woff2")
                else -> ContentType.Application.OctetStream
            }
            call.respondOutputStream(contentType) {
                inputStream.use { it.copyTo(this) }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    get("/") {
        call.noStore()
        val mode = call.request.queryParameters["mode"]
        val pathParam = call.request.queryParameters["path"] ?: ""
        val themeParam = call.request.queryParameters["theme"] ?: "dark"

        val authRequired = server.authManager.isAuthEnabled()
        val authorized = server.authManager.isAuthorized(call)
        if (authRequired && !authorized) {
            call.respondOutputStream(ContentType.Text.Html) {
                val writer = BufferedWriter(OutputStreamWriter(this, Charsets.UTF_8))
                with(LoginPageTemplate) {
                    writer.respondLoginPage(themeParam = themeParam)
                }
            }
            return@get
        }

        call.respondOutputStream(ContentType.Text.Html) {
            val writer = BufferedWriter(OutputStreamWriter(this, Charsets.UTF_8))
            with(HomePageTemplate) {
                writer.respondHomePage(
                    mode = mode,
                    pathParam = pathParam,
                    themeParam = themeParam
                )
            }
        }
    }

    get("/diagnostics") {
        call.noStore()
        val themeParam = call.request.queryParameters["theme"] ?: "dark"
        val authRequired = server.authManager.isAuthEnabled()
        val authorized = server.authManager.isAuthorized(call)
        if (authRequired && !authorized) {
            call.respondOutputStream(ContentType.Text.Html) {
                val writer = BufferedWriter(OutputStreamWriter(this, Charsets.UTF_8))
                with(LoginPageTemplate) {
                    writer.respondLoginPage(themeParam = themeParam)
                }
            }
            return@get
        }

        call.respondOutputStream(ContentType.Text.Html) {
            val writer = BufferedWriter(OutputStreamWriter(this, Charsets.UTF_8))
            with(DiagnosticsPageTemplate) {
                writer.respondDiagnosticsPage(themeParam = themeParam)
            }
        }
    }

    get("/manifest.json") {
        call.respondText(
            """
            {
                "name": "LANflix",
                "short_name": "LANflix",
                "description": "Stream local media over your LAN",
                "start_url": "/?theme=dark",
                "display": "standalone",
                "background_color": "#0a0a0a",
                "theme_color": "#0a0a0a",
                "orientation": "any",
                "icons": [
                    {
                        "src": "/api/icon/192",
                        "sizes": "192x192",
                        "type": "image/png"
                    },
                    {
                        "src": "/api/icon/512",
                        "sizes": "512x512",
                        "type": "image/png"
                    }
                ]
            }
            """.trimIndent(),
            ContentType.Application.Json
        )
    }

    get("/sw.js") {
        call.noStore()
        call.respondText(
            """
            const CACHE_NAME = 'lanflix-v4';
            const OFFLINE_URL = '/offline.html';
            
            self.addEventListener('install', (event) => {
                self.skipWaiting();
                event.waitUntil(
                    caches.open(CACHE_NAME).then((cache) => {
                        return cache.addAll([
                            '/',
                            '/manifest.json',
                            '/api/icon/180',
                            '/api/icon/192',
                            '/static/css/spline-sans.css',
                            '/static/css/material-symbols.css',
                            '/static/js/tailwind.min.js'
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
                
                if (url.pathname.startsWith('/api/')) {
                    event.respondWith(
                        fetch(event.request).catch(() => {
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
                    event.respondWith(
                        caches.match(event.request).then((response) => {
                            return response || fetch(event.request);
                        })
                    );
                }
            });
        """.trimIndent(),
            ContentType.parse("application/javascript")
        )
    }
}
