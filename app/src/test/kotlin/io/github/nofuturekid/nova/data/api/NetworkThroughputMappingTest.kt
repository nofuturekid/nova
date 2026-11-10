package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.selectThroughput
import io.github.nofuturekid.nova.graphql.GetNetworkThroughputQuery
import io.github.nofuturekid.nova.graphql.SystemMetricsNetworkSubscription
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkThroughputMappingTest {
    @Test fun poll_frame_maps_rates_to_selected_primary() {
        // WHY: Unraid API 4.35+ serves `metrics.network` as a list of
        // NetworkMetrics{name rxSec txSec}. A dropped WS falls back to the 2 s poll,
        // which must pick the primary iface (never `lo`, never an idle other NIC).
        val data = data(net("lo", 5.0, 5.0), net("eth1", 0.0, 0.0), net("eth0", 3855.0, 3797.0))
        assertEquals(NetworkThroughput(3855.0, 3797.0), selectThroughput(data.toIfaceSamples(), "eth0"))
    }

    @Test fun virtual_interfaces_are_never_picked_by_the_fallback() {
        // WHY: 4.35+ lists docker0/veth*/vnet*/shim-* too. If the primary NIC is
        // unknown, "first non-lo interface that moves bytes" must not land on a
        // container's veth and show its traffic as the server's.
        val data = data(
            net("docker0", 900.0, 10000.0),
            net("veth19ce726", 1000.0, 14000.0),
            net("vnet0", 900.0, 1600.0),
            net("shim-br0", 13000.0, 9800.0),
            net("br-3f9a1c2d4e5b", 700.0, 800.0),
            net("br0", 17421.0, 23315.0),
        )
        assertEquals(NetworkThroughput(17421.0, 23315.0), selectThroughput(data.toIfaceSamples(), null))
    }

    @Test fun null_metrics_maps_to_zero() {
        // WHY: `metrics` is nullable on the query side; no data must degrade to
        // ZERO without an NPE, not crash the fallback.
        assertEquals(
            NetworkThroughput.ZERO,
            selectThroughput(GetNetworkThroughputQuery.Data(metrics = null).toIfaceSamples(), "br0"),
        )
    }

    @Test fun subscription_frame_maps_and_filters_like_the_poll() {
        // WHY: the subscription is the PRIMARY transport and its generated type is a
        // separate copy of the poll one; it must map name/rxSec/txSec and drop virtual
        // interfaces identically, or the card would differ between WS and fallback.
        val frame = SystemMetricsNetworkSubscription.Data(
            systemMetricsNetwork = listOf(
                SystemMetricsNetworkSubscription.SystemMetricsNetwork("veth19ce726", 1000.0, 14000.0),
                SystemMetricsNetworkSubscription.SystemMetricsNetwork("br-3f9a1c2d4e5b", 700.0, 800.0),
                SystemMetricsNetworkSubscription.SystemMetricsNetwork("br0", 17421.0, 23315.0),
            ),
        )
        assertEquals(NetworkThroughput(17421.0, 23315.0), selectThroughput(frame.toIfaceSamples(), null))
    }

    private fun data(vararg n: GetNetworkThroughputQuery.Network) =
        GetNetworkThroughputQuery.Data(metrics = GetNetworkThroughputQuery.Metrics(network = n.toList()))

    private fun net(name: String, rx: Double, tx: Double) =
        GetNetworkThroughputQuery.Network(name = name, rxSec = rx, txSec = tx)
}
