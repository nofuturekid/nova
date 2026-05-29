package io.github.nofuturekid.nova.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {
    @Test fun zero_is_bytes() = assertEquals("0 B/s", formatBytesPerSec(0.0))
    @Test fun just_under_kilo_is_bytes() = assertEquals("999 B/s", formatBytesPerSec(999.0))
    @Test fun kilo_boundary() = assertEquals("1.0 KB/s", formatBytesPerSec(1_000.0))
    @Test fun low_kb_is_honest() = assertEquals("3.3 KB/s", formatBytesPerSec(3_300.0))
    @Test fun megabytes() = assertEquals("12.0 MB/s", formatBytesPerSec(12_000_000.0))
    @Test fun gigabytes() = assertEquals("1.5 GB/s", formatBytesPerSec(1_500_000_000.0))
    @Test fun negative_clamped_to_zero() = assertEquals("0 B/s", formatBytesPerSec(-5.0))
}
