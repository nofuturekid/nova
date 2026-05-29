package io.github.nofuturekid.nova.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkThroughputTest {
    private val lo = IfaceSample("lo", 5.0, 5.0)
    private val eth0 = IfaceSample("eth0", 3300.0, 2800.0)
    private val eth1 = IfaceSample("eth1", 0.0, 0.0)

    @Test fun picks_named_primary() =
        assertEquals(NetworkThroughput(3300.0, 2800.0), selectThroughput(listOf(lo, eth0, eth1), "eth0"))

    @Test fun never_picks_loopback_even_if_named() =
        assertEquals(NetworkThroughput(0.0, 0.0), selectThroughput(listOf(lo), "lo"))

    @Test fun fallback_to_active_non_lo_when_name_absent() =
        assertEquals(NetworkThroughput(3300.0, 2800.0), selectThroughput(listOf(lo, eth1, eth0), null))

    @Test fun fallback_to_first_non_lo_when_all_idle() =
        assertEquals(NetworkThroughput(0.0, 0.0), selectThroughput(listOf(lo, eth1), null))

    @Test fun empty_is_zero() =
        assertEquals(NetworkThroughput.ZERO, selectThroughput(emptyList(), "eth0"))
}
