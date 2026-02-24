package com.thiyagu.media_server.server

import com.thiyagu.media_server.server.routes.registerCacheRoutes
import com.thiyagu.media_server.server.routes.registerCastRoutes
import com.thiyagu.media_server.server.routes.registerDiagnosticsRoutes
import com.thiyagu.media_server.server.routes.registerHealthRoutes
import com.thiyagu.media_server.server.routes.registerStreamingRoutes
import com.thiyagu.media_server.server.routes.registerWebRoutes
import io.ktor.server.routing.Routing

fun Routing.configureServerRoutes(server: KtorMediaStreamingServer) {
    registerHealthRoutes(server)
    registerCacheRoutes(server)
    registerCastRoutes(server)
    registerDiagnosticsRoutes(server)
    registerWebRoutes(server)
    registerStreamingRoutes(server)
}
