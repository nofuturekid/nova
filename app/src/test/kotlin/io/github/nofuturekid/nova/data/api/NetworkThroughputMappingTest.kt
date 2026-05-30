package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.selectThroughput
import io.github.nofuturekid.nova.graphql.GetNetworkThroughputQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkThroughputMappingTest {
    @Test fun poll_frame_maps_through_to_selected_primary() {
        // WHY: a dropped WS must fall back to the 2 s `GetNetworkThroughput`
        // poll, not a blank card. That fallback only works if the QUERY-side
        // frame (a DIFFERENT generated type from the subscription) projects to
        // the same IfaceSample shape and selectThroughput picks the same primary
        // iface — never `lo`, never the idle non-primary. This pins the poll
        // projection (added 2026-05-30 once `metrics.network` appeared upstream).
        val data = networkData(
            iface("lo", 5.0, 5.0),
            iface("eth1", 0.0, 0.0),
            iface("eth0", 3855.0, 3797.0),
        )
        assertEquals(
            NetworkThroughput(3855.0, 3797.0),
            selectThroughput(data.toIfaceSamples(), "eth0"),
        )
    }

    @Test fun null_rates_default_to_zero() {
        // WHY: rxBytesPerSec/txBytesPerSec are nullable on the live server; a
        // null must map to 0.0 on the poll path (same as the subscription path),
        // so the chosen interface still yields a usable NetworkThroughput.
        val data = networkData(iface("eth0", null, null))
        assertEquals(
            NetworkThroughput(0.0, 0.0),
            selectThroughput(data.toIfaceSamples(), "eth0"),
        )
    }

    @Test fun null_network_root_maps_to_zero() {
        // WHY: `metrics` and `metrics.network` are BOTH nullable on the query
        // side (unlike the non-null subscription root). A frame with no network
        // data must degrade to ZERO without NPE, not crash the fallback.
        val data = GetNetworkThroughputQuery.Data(
            metrics = GetNetworkThroughputQuery.Metrics(network = null),
        )
        assertEquals(NetworkThroughput.ZERO, selectThroughput(data.toIfaceSamples(), "eth0"))
    }

    private fun networkData(vararg interfaces: GetNetworkThroughputQuery.Interface) =
        GetNetworkThroughputQuery.Data(
            metrics = GetNetworkThroughputQuery.Metrics(
                network = GetNetworkThroughputQuery.Network(interfaces = interfaces.toList()),
            ),
        )

    private fun iface(name: String, rx: Double?, tx: Double?) =
        GetNetworkThroughputQuery.Interface(
            iface = name,
            rxBytesPerSec = rx,
            txBytesPerSec = tx,
        )
}
