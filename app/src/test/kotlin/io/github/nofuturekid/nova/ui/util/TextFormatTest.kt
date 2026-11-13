package io.github.nofuturekid.nova.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Why: #226 — "1 alerts · 1 warnings" read wrong and the long memory pair truncated at 411 dp. */
class TextFormatTest {
    @Test fun singular_and_plural_headers() {
        assertEquals("1 alert · 1 warning · 2 info", notificationSummary(1, 1, 2))
        assertEquals("0 alerts · 2 warnings · 1 info", notificationSummary(0, 2, 1))
    }

    @Test fun mem_used_drops_the_limit() {
        assertEquals("1.15GiB", memUsed("1.15GiB / 31.3GiB"))
        assertEquals("31.3GiB", memLimit("1.15GiB / 31.3GiB"))
    }

    @Test fun empty_mem_shows_a_dash_not_a_blank() {
        assertEquals("—", memUsed(""))
    }

    @Test fun mem_without_separator_is_kept_whole() {
        assertEquals("236MiB", memUsed("236MiB"))
        assertNull(memLimit("236MiB"))
    }

    // #243: the Unraid API exposes no vCPU / memory / GPU, so these are unknown (null)
    // and must never render as "0 vCPU · 0 GB".
    @Test fun unknown_vm_specs_render_nothing() {
        assertNull(vmSpecSummary(null, null, null))
        assertEquals(emptyList<Pair<String, String>>(), vmSpecRows(null, null, null))
    }

    @Test fun known_vm_specs_skip_only_the_unknown_parts() {
        assertEquals("4 vCPU · 8 GB · RTX 3060", vmSpecSummary(4, 8, "RTX 3060"))
        assertEquals("2 GB", vmSpecSummary(null, 2, null))
        assertEquals("4 vCPU", vmSpecSummary(4, null, null))
        assertEquals(
            listOf("vCPUs" to "4", "Memory" to "8 GB", "GPU" to "RTX 3060"),
            vmSpecRows(4, 8, "RTX 3060"),
        )
        assertEquals(listOf("Memory" to "2 GB"), vmSpecRows(null, 2, null))
    }

    // #304: the API returns the boot TIME ("2026-11-12T13:56:22.425Z"); the card showed it raw.
    // The clock is fixed so the expected durations do not depend on when the test runs.
    private val boot = "2026-11-12T13:56:22.425Z"
    private fun clockAt(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test fun uptime_is_now_minus_boot_time_not_the_timestamp() {
        val label = uptimeLabel(boot, clockAt("2026-11-13T12:00:22.425Z"))
        assertEquals("22h 4m", label)
        assertFalse(label.contains("2026"))
    }

    @Test fun uptime_covers_minutes_hours_and_days() {
        assertEquals("<1m", uptimeLabel(boot, clockAt("2026-11-12T13:56:50Z")))
        assertEquals("12m", uptimeLabel(boot, clockAt("2026-11-12T14:08:22.425Z")))
        assertEquals("1h 0m", uptimeLabel(boot, clockAt("2026-11-12T14:56:22.425Z")))
        assertEquals("23h 59m", uptimeLabel(boot, clockAt("2026-11-13T13:55:22.425Z")))
        assertEquals("1d 0h", uptimeLabel(boot, clockAt("2026-11-13T13:56:22.425Z")))
        assertEquals("3d 4h", uptimeLabel(boot, clockAt("2026-11-15T18:00:00Z")))
    }

    @Test fun uptime_respects_the_offset_of_the_boot_time() {
        // Same instant as 13:56:22Z, written with an offset.
        assertEquals("1h 0m", uptimeLabel("2026-11-12T14:56:22+01:00", clockAt("2026-11-12T14:56:22Z")))
    }

    @Test fun missing_or_unreadable_boot_time_shows_a_dash_not_the_raw_text() {
        val clock = clockAt("2026-11-13T12:00:00Z")
        assertEquals("—", uptimeLabel(null, clock))
        assertEquals("—", uptimeLabel("", clock))
        assertEquals("—", uptimeLabel("   ", clock))
        assertEquals("—", uptimeLabel("up 3 days", clock))
        assertEquals("—", uptimeLabel("2026-11-12 13:56:22", clock))
    }

    @Test fun a_boot_time_in_the_future_is_unknown_not_a_negative_duration() {
        assertEquals("—", uptimeLabel("2026-11-14T00:00:00Z", clockAt("2026-11-13T12:00:00Z")))
    }
}
