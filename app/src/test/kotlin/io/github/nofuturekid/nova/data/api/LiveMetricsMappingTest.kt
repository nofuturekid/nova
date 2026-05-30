package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.LiveMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMetricsMappingTest {
    private val GiB = 1_073_741_824L

    // WHY: the live cpu+mem combine must yield the SAME LiveMetrics as the
    // GetMetrics poll (toLiveMetrics), so swapping WS<->poll is invisible.
    // Pins the binary-GiB divisor (RAM uses GiB, not decimal GB) and the
    // straight passthrough of cpuPercent.
    @Test fun converts_bytes_to_binary_gib_like_the_poll() {
        val m = combineLiveMetrics(
            cpuPercent = 37.5,
            memTotal = 8 * GiB,
            memUsed = 2 * GiB,
            memBuffcache = 1 * GiB,
        )
        assertEquals(LiveMetrics(37.5, 8.0, 2.0, 1.0), m)
    }

    // WHY: MemoryUtilization.buffcache is nullable on the live server. The
    // poll maps null -> 0L -> 0.0 GB. The live mapper MUST match, or a card
    // would read differently on the two transports.
    @Test fun null_buffcache_is_zero_gb_matching_the_poll() {
        val m = combineLiveMetrics(
            cpuPercent = 0.0,
            memTotal = 4 * GiB,
            memUsed = 1 * GiB,
            memBuffcache = null,
        )
        assertEquals(LiveMetrics(0.0, 4.0, 1.0, 0.0), m)
    }
}
