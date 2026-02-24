package com.thiyagu.media_server.server.routes

import com.thiyagu.media_server.server.KtorMediaStreamingServer
import io.ktor.server.routing.Routing

internal fun Routing.registerStreamingRoutes(
    server: KtorMediaStreamingServer
) {
    registerThumbnailRoutes(server)
    registerStreamRoutes(server)
    registerExistsRoutes(server)
}
