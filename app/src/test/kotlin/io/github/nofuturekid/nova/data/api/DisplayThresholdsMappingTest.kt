package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.DisplayThresholds
import io.github.nofuturekid.nova.graphql.GetDisplayQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies that [GetDisplayQuery.Data.toDisplayThresholds] maps the server's
 * dynamix display fields to [DisplayThresholds] correctly.
 *
 * WHY: global thresholds prevent false disk-temp alarms when per-disk thresholds
 * are null (the common case on most Unraid servers). Mapping correctness is the
 * contract between the GraphQL response and the domain model that drives coloring
 * in both the Array tab and the Overview CPU card (ADR-0045 beta9 update).
 *
 * Field mapping: hot → diskWarnC, max → diskCritC, warning → cpuWarnC, critical → cpuCritC.
 * Live-verified values 2026-05-30: hot=55, max=60, warning=85, critical=95.
 */
class DisplayThresholdsMappingTest {

    @Test fun all_fields_set_maps_correctly() {
        // WHY: verifies the live-server values map to the expected domain fields.
        // hot=55 is global disk warn; max=60 is global disk crit;
        // warning=85 is CPU warn; critical=95 is CPU crit.
        val data = displayData(hot = 55, max = 60, warning = 85, critical = 95)
        assertEquals(
            DisplayThresholds(diskWarnC = 55, diskCritC = 60, cpuWarnC = 85, cpuCritC = 95),
            data.toDisplayThresholds(),
        )
    }

    @Test fun null_display_block_yields_all_null() {
        // WHY: the server may not expose the display field at all on older Unraid
        // versions; the mapper must degrade gracefully to all-null thresholds,
        // so callers fall back to hardcoded defaults without crashing.
        val data = GetDisplayQuery.Data(display = null)
        assertEquals(
            DisplayThresholds(diskWarnC = null, diskCritC = null, cpuWarnC = null, cpuCritC = null),
            data.toDisplayThresholds(),
        )
    }

    @Test fun partial_fields_carry_through_nulls() {
        // WHY: some servers may only configure some thresholds; the mapper must
        // pass through whatever is present and leave the rest null.
        val data = displayData(hot = 50, max = null, warning = null, critical = 90)
        val result = data.toDisplayThresholds()
        assertEquals(50, result.diskWarnC)
        assertNull(result.diskCritC)
        assertNull(result.cpuWarnC)
        assertEquals(90, result.cpuCritC)
    }

    // ── Builder ───────────────────────────────────────────────────────

    private fun displayData(
        hot: Int? = null,
        max: Int? = null,
        warning: Int? = null,
        critical: Int? = null,
    ) = GetDisplayQuery.Data(
        display = GetDisplayQuery.Display(
            hot = hot,
            max = max,
            warning = warning,
            critical = critical,
        ),
    )
}
