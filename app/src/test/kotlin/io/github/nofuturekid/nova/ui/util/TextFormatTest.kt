package io.github.nofuturekid.nova.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
