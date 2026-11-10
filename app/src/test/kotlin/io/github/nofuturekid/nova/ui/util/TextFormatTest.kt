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

    @Test fun mem_without_separator_is_kept_whole() {
        assertEquals("236MiB", memUsed("236MiB"))
        assertNull(memLimit("236MiB"))
    }
}
