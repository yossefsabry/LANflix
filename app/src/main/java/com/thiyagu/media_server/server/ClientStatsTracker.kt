package com.thiyagu.media_server.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ConnectionStats(
    val connectedDevices: Int,
    val streamingDevices: Int,
    val activeStreams: Int
)

class ClientStatsTracker(
    private val deviceTtlMs: Long = DEFAULT_DEVICE_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val DEFAULT_DEVICE_TTL_MS: Long = 20_000L
    }

    private val lastSeenMsByClientKey = ConcurrentHashMap<String, Long>()
    private val activeStreamsByClientKey = ConcurrentHashMap<String, AtomicInteger>()

    fun markSeen(clientKey: String) {
        lastSeenMsByClientKey[clientKey] = clockMs()
    }

    fun startStreaming(clientKey: String) {
        markSeen(clientKey)
        activeStreamsByClientKey.compute(clientKey) { _, existing ->
            if (existing == null) {
                AtomicInteger(1)
            } else {
                existing.incrementAndGet()
                existing
            }
        }
    }

    fun stopStreaming(clientKey: String) {
        val counter = activeStreamsByClientKey[clientKey] ?: return
        val remaining = counter.decrementAndGet()
        if (remaining <= 0) {
            activeStreamsByClientKey.remove(clientKey, counter)
        }
    }

    fun markDisconnected(clientKey: String) {
        lastSeenMsByClientKey.remove(clientKey)
        val active = activeStreamsByClientKey[clientKey]?.get() ?: 0
        if (active <= 0) {
            activeStreamsByClientKey.remove(clientKey)
        }
    }

    fun pruneStaleClients() {
        val now = clockMs()
        for ((clientKey, lastSeenMs) in lastSeenMsByClientKey) {
            val isStreaming = (activeStreamsByClientKey[clientKey]?.get() ?: 0) > 0
            if (!isStreaming && now - lastSeenMs > deviceTtlMs) {
                lastSeenMsByClientKey.remove(clientKey, lastSeenMs)
            }
        }
    }

    fun getStats(): ConnectionStats {
        pruneStaleClients()

        val now = clockMs()
        val connectedKeys = HashSet<String>()

        for ((clientKey, lastSeenMs) in lastSeenMsByClientKey) {
            if (now - lastSeenMs <= deviceTtlMs) {
                connectedKeys.add(clientKey)
            }
        }

        var streamingDevices = 0
        var activeStreams = 0
        for ((clientKey, count) in activeStreamsByClientKey) {
            val streamsForClient = count.get()
            if (streamsForClient > 0) {
                streamingDevices += 1
                activeStreams += streamsForClient
                connectedKeys.add(clientKey)
            }
        }

        return ConnectionStats(
            connectedDevices = connectedKeys.size,
            streamingDevices = streamingDevices,
            activeStreams = activeStreams
        )
    }
}
