package io.github.nofuturekid.nova.ui.util

/** Adaptive decimal throughput formatter: B/s · KB/s · MB/s · GB/s. */
fun formatBytesPerSec(bytesPerSec: Double): String {
    val b = bytesPerSec.coerceAtLeast(0.0)
    return when {
        b < 1_000.0 -> "%.0f B/s".format(b)
        b < 1_000_000.0 -> "%.1f KB/s".format(b / 1_000.0)
        b < 1_000_000_000.0 -> "%.1f MB/s".format(b / 1_000_000.0)
        else -> "%.1f GB/s".format(b / 1_000_000_000.0)
    }
}
