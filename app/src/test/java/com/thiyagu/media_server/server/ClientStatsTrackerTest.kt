package com.thiyagu.media_server.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientStatsTrackerTest {

    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    @Test
    fun markSeen_tracksConnectedDevices() {
        val clock = FakeClock()
        val tracker = ClientStatsTracker(deviceTtlMs = 1_000, clockMs = { clock.nowMs })

        tracker.markSeen("a")
        tracker.markSeen("b")

        val stats = tracker.getStats()
        assertEquals(2, stats.connectedDevices)
        assertEquals(0, stats.streamingDevices)
        assertEquals(0, stats.activeStreams)
    }

    @Test
    fun prune_removesStaleClients() {
        val clock = FakeClock()
        val tracker = ClientStatsTracker(deviceTtlMs = 1_000, clockMs = { clock.nowMs })

        tracker.markSeen("a")
        clock.advance(1_001)

        val stats = tracker.getStats()
        assertEquals(0, stats.connectedDevices)
    }

    @Test
    fun streaming_countsUniqueDevicesAndTotalStreams() {
        val clock = FakeClock()
        val tracker = ClientStatsTracker(deviceTtlMs = 1_000, clockMs = { clock.nowMs })

        tracker.startStreaming("a")
        tracker.startStreaming("a")
        tracker.startStreaming("b")

        val stats = tracker.getStats()
        assertEquals(2, stats.connectedDevices)
        assertEquals(2, stats.streamingDevices)
        assertEquals(3, stats.activeStreams)
    }

    @Test
    fun stopStreaming_removesClientWhenCountReachesZero() {
        val clock = FakeClock()
        val tracker = ClientStatsTracker(deviceTtlMs = 1_000, clockMs = { clock.nowMs })

        tracker.startStreaming("a")
        tracker.stopStreaming("a")

        val stats = tracker.getStats()
        assertEquals(0, stats.streamingDevices)
        assertEquals(0, stats.activeStreams)
    }

    @Test
    fun connectedDevices_includesStreamingClientsEvenWhenLastSeenIsStale() {
        val clock = FakeClock()
        val tracker = ClientStatsTracker(deviceTtlMs = 1_000, clockMs = { clock.nowMs })

        tracker.startStreaming("a")
        clock.advance(1_001)

        val stats = tracker.getStats()
        assertEquals(1, stats.connectedDevices)
        assertEquals(1, stats.streamingDevices)
        assertEquals(1, stats.activeStreams)
    }

    @Test
    fun connectedDevices_dropAfterStreamEndsAndTtlPasses() {
        val clock = FakeClock()
        val tracker = ClientStatsTracker(deviceTtlMs = 1_000, clockMs = { clock.nowMs })

        tracker.startStreaming("a")
        clock.advance(1_001)
        tracker.stopStreaming("a")

        val stats = tracker.getStats()
        assertEquals(0, stats.connectedDevices)
        assertEquals(0, stats.streamingDevices)
        assertEquals(0, stats.activeStreams)
    }
}

