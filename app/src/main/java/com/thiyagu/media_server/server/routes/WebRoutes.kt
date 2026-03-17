package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import com.thiyagu.media_server.server.pages.DiagnosticsPageTemplate
import com.thiyagu.media_server.server.pages.HomePageTemplate
import com.thiyagu.media_server.server.pages.LoginPageTemplate
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.BufferedWriter
import java.io.OutputStreamWriter

internal fun Routing.registerWebRoutes(server: KtorMediaStreamingServer) {
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

    get("/sw.js") {
        call.noStore()
        call.respondText(
            """
            const CACHE_NAME = 'lanflix-v3';
            const OFFLINE_URL = '/offline.html';
            
            self.addEventListener('install', (event) => {
                self.skipWaiting();
                event.waitUntil(
                    caches.open(CACHE_NAME).then((cache) => {
                        return cache.addAll([
                            '/',
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
