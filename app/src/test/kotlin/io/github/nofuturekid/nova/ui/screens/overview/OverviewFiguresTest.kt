package io.github.nofuturekid.nova.ui.screens.overview

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Why: #303 — offline, the Overview showed "0,0 / 0 TB used" with a half-filled
 * bar, "CPU 0,0%" and "0% of 0 GB" next to the Offline badge. A value that is
 * not known is not a zero: it must read as a placeholder, while a zero the
 * server really reports must still read as zero.
 */
class OverviewFiguresTest {
    private val savedLocale = Locale.getDefault()

    // The cards format with the device locale; pin it so the assertions are the same on every machine.
    @Before fun pinLocale() = Locale.setDefault(Locale.US)
    @After fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun array(total: Double, used: Double) =
        ArrayInfo(ArrayState.Started, totalTb = total, usedTb = used, parity = null, disks = emptyList())

    // ── Array card ───────────────────────────────────────────────────

    @Test fun unknown_array_shows_the_placeholder_and_no_bar() {
        val f = arrayFigures(null)
        assertEquals(PLACEHOLDER, f.usedText)
        assertEquals(PLACEHOLDER, f.percentText)
        assertEquals(PLACEHOLDER, f.freeText)
        // The half-filled bar came from two equal segments of an unknown total.
        assertNull(f.barWeights)
        assertNotEquals("0.0", f.usedText)
    }

    @Test fun known_array_keeps_its_numbers_and_bar() {
        val f = arrayFigures(array(total = 20.0, used = 5.0))
        assertEquals("5.0", f.usedText)
        assertEquals("/ 20 TB used", f.totalText)
        assertEquals("25% used", f.percentText)
        assertEquals("15.0 TB free", f.freeText)
        assertEquals(5f to 15f, f.barWeights)
    }

    @Test fun a_real_zero_array_stays_zero_but_draws_no_share() {
        val f = arrayFigures(array(total = 0.0, used = 0.0))
        assertEquals("0.0", f.usedText)
        assertEquals("/ 0 TB used", f.totalText)
        assertEquals("0% used", f.percentText)
        assertEquals("0.0 TB free", f.freeText)
        assertNull(f.barWeights)
    }

    @Test fun an_empty_but_known_array_has_a_bar_that_is_all_free() {
        val f = arrayFigures(array(total = 8.0, used = 0.0))
        assertEquals("0.0", f.usedText)
        assertEquals(0f to 8f, f.barWeights)
    }

    // ── CPU card ─────────────────────────────────────────────────────

    @Test fun unknown_cpu_load_is_a_placeholder_not_zero_percent() {
        assertEquals(PLACEHOLDER, cpuValue(null))
    }

    @Test fun a_real_zero_cpu_load_stays_zero() {
        assertEquals("0.0%", cpuValue(0.0))
        assertEquals("14.5%", cpuValue(14.5))
    }

    @Test fun cpu_sub_line_has_no_dangling_parts() {
        assertEquals("8 cores · Ryzen 7", cpuSub(8, "Ryzen 7", loadKnown = true))
        assertEquals("8 cores", cpuSub(8, "", loadKnown = true))
        assertEquals("Ryzen 7", cpuSub(null, "Ryzen 7", loadKnown = true))
    }

    @Test fun cpu_sub_line_without_any_info_says_what_is_missing() {
        // "0 cores · " was shown while offline.
        assertEquals(LIVE_UNAVAILABLE, cpuSub(null, null, loadKnown = false))
        assertEquals(PLACEHOLDER, cpuSub(null, null, loadKnown = true))
    }

    // ── Memory card ──────────────────────────────────────────────────

    @Test fun unknown_memory_is_a_placeholder_not_zero_gb() {
        val f = memoryFigures(usedGb = null, cachedGb = null, totalGb = null)
        assertEquals(PLACEHOLDER, f.value)
        assertEquals(LIVE_UNAVAILABLE, f.sub)
        assertNull(f.percent)
    }

    @Test fun known_memory_keeps_its_numbers() {
        val f = memoryFigures(usedGb = 8.0, cachedGb = 2.5, totalGb = 32.0)
        assertEquals("8.0 GB", f.value)
        assertEquals("25% of 32 GB · 2.5 GB cached", f.sub)
        assertEquals(25f, f.percent)
    }

    @Test fun a_real_zero_memory_use_stays_zero() {
        val f = memoryFigures(usedGb = 0.0, cachedGb = 0.0, totalGb = 32.0)
        assertEquals("0.0 GB", f.value)
        assertEquals("0% of 32 GB · 0.0 GB cached", f.sub)
        assertEquals(0f, f.percent)
    }

    @Test fun a_total_of_zero_is_unknown_so_no_percent_of_zero_gb() {
        // Some virtualised setups report a total of 0; "0% of 0 GB" is never true.
        val f = memoryFigures(usedGb = 4.0, cachedGb = 1.0, totalGb = 0.0)
        assertEquals("4.0 GB", f.value)
        assertEquals("1.0 GB cached", f.sub)
        assertNull(f.percent)
    }

    // ── Sparkline buffers ────────────────────────────────────────────

    @Test fun a_missing_sample_empties_the_graph_instead_of_adding_a_zero() {
        val live = rollSeries(rollSeries(emptyList(), 40f), 42f)
        assertNotNull(live.maxOrNull())
        assertEquals(emptyList<Float>(), rollSeries(live, null))
    }

    @Test fun the_first_sample_fills_the_graph_and_later_ones_roll_it() {
        val first = rollSeries(emptyList(), 7f)
        assertEquals(SERIES_LENGTH, first.size)
        assertEquals(List(SERIES_LENGTH) { 7f }, first)
        val next = rollSeries(first, 9f)
        assertEquals(SERIES_LENGTH, next.size)
        assertEquals(9f, next.last())
        assertEquals(7f, next[SERIES_LENGTH - 2])
    }

    @Test fun a_real_zero_sample_is_kept_as_a_point() {
        val next = rollSeries(rollSeries(emptyList(), 5f), 0f)
        assertEquals(0f, next.last())
        assertEquals(SERIES_LENGTH, next.size)
    }
}
