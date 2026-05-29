package io.github.nofuturekid.nova.data.model

/** Live per-second throughput for one interface. */
data class NetworkThroughput(
    val rxBytesPerSec: Double,
    val txBytesPerSec: Double,
) {
    companion object { val ZERO = NetworkThroughput(0.0, 0.0) }
}

/** Plain per-interface sample — the pure seam the Apollo mapper feeds, so
 *  interface-selection logic unit-tests without generated types. */
data class IfaceSample(
    val iface: String?,
    val rxBytesPerSec: Double,
    val txBytesPerSec: Double,
)

/**
 * Pick the throughput to display from a frame's interfaces.
 * Preference: the named management interface → first non-`lo` that is
 * actively moving bytes → first non-`lo` → ZERO. `lo` is never chosen.
 */
fun selectThroughput(samples: List<IfaceSample>, primaryIface: String?): NetworkThroughput {
    val chosen = samples.firstOrNull { it.iface == primaryIface && it.iface != "lo" }
        ?: samples.firstOrNull { it.iface != "lo" && (it.rxBytesPerSec + it.txBytesPerSec) > 0.0 }
        ?: samples.firstOrNull { it.iface != "lo" }
    return chosen?.let { NetworkThroughput(it.rxBytesPerSec, it.txBytesPerSec) } ?: NetworkThroughput.ZERO
}
