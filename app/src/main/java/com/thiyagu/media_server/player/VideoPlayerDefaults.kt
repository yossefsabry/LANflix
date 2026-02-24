package com.thiyagu.media_server.player

internal object VideoPlayerDefaults {
    const val DEFAULT_PORT = 8888
    const val SEEK_INCREMENT_MS = 10_000L
    const val RESUME_MIN_POSITION_MS = 4_000L
    const val RESUME_SAVE_INTERVAL_MS = 4_000L
    const val RESUME_MIN_DELTA_MS = 2_000L
    const val RESUME_END_GUARD_MS = 15_000L
    const val MAX_RETRY_COUNT = 6
    const val RETRY_BASE_DELAY_MS = 1500L
    const val RETRY_MAX_DELAY_MS = 20_000L
    const val REDISCOVERY_TIMEOUT_MS = 6_000L
    const val REDISCOVERY_POLL_MS = 500L
    const val REDISCOVERY_CONNECT_TIMEOUT_MS = 2000
    const val REDISCOVERY_READ_TIMEOUT_MS = 2000
    const val KEEPALIVE_INTERVAL_MS = 20_000L
    const val KEEPALIVE_CONNECT_TIMEOUT_MS = 1500
    const val KEEPALIVE_READ_TIMEOUT_MS = 1500
}
