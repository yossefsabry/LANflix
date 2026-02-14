package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import com.thiyagu.media_server.server.pages.DiagnosticsPageTemplate
import com.thiyagu.media_server.server.pages.HomePageTemplate
import com.thiyagu.media_server.server.pages.LoginPageTemplate
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.io.BufferedWriter
import java.io.OutputStreamWriter

internal fun Routing.registerWebRoutes(server: KtorMediaStreamingServer) {
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
