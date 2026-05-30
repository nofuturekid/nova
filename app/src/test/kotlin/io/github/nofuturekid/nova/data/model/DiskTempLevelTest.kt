package io.github.nofuturekid.nova.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Disk.tempLevel] — the pure helper that maps disk state to a
 * [DiskTempLevel] without Compose on the classpath.
 *
 * WHY these cases matter:
 * - A spun-down disk returns temp=null from the server (coalesced to tempC=0 by
 *   the mapper). Without isSpinning-gating the UI would show "0°" for a sleeping
 *   disk — the primary bug driving ADR-0045.
 * - Per-disk warning/critical thresholds from Unraid override the app defaults;
 *   a tighter threshold (e.g. warningC=40) must fire even when the fallback (42)
 *   would not. Verified server behaviour 2026-05-30.
 */
class DiskTempLevelTest {

    // ── Standby / sleep branch ────────────────────────────────────────

    @Test fun spinning_false_is_standby_regardless_of_temp() {
        // WHY: server returns temp=null when spun down → tempC=0 after coalesce.
        // isSpinning=false is the ONLY truth source — a sleeping disk must NEVER
        // render "0°".
        val disk = disk(isSpinning = false, tempC = 0)
        assertEquals(DiskTempLevel.Standby, disk.tempLevel())
    }

    @Test fun spinning_false_is_standby_even_with_nonzero_temp() {
        // WHY: defensive — if the server ever returns a temp alongside isSpinning=false,
        // the sleep state still wins.
        val disk = disk(isSpinning = false, tempC = 55)
        assertEquals(DiskTempLevel.Standby, disk.tempLevel())
    }

    // ── Fallback (null) thresholds ────────────────────────────────────

    @Test fun spinning_cold_with_null_thresholds_is_normal() {
        // WHY: a healthy cool spinning disk with no server-side thresholds configured
        // must report Normal — no false alarms.
        val disk = disk(isSpinning = true, tempC = 39, warningC = null, criticalC = null)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel())
    }

    @Test fun spinning_at_fallback_warn_threshold_is_warn() {
        // WHY: 42 °C is the app's default warning level. At exactly 42 with no
        // per-disk thresholds, the result must be Warn, not Normal.
        val disk = disk(isSpinning = true, tempC = 42, warningC = null, criticalC = null)
        assertEquals(DiskTempLevel.Warn, disk.tempLevel())
    }

    @Test fun spinning_above_fallback_danger_threshold_is_danger() {
        // WHY: 50 °C is the app's default critical level (50+). The null-threshold
        // fallback must still catch a clearly hot disk.
        val disk = disk(isSpinning = true, tempC = 52, warningC = null, criticalC = null)
        assertEquals(DiskTempLevel.Danger, disk.tempLevel())
    }

    @Test fun spinning_just_below_fallback_warn_is_normal() {
        val disk = disk(isSpinning = true, tempC = 41, warningC = null, criticalC = null)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel())
    }

    @Test fun spinning_at_fallback_danger_threshold_is_danger() {
        val disk = disk(isSpinning = true, tempC = 50, warningC = null, criticalC = null)
        assertEquals(DiskTempLevel.Danger, disk.tempLevel())
    }

    // ── Per-disk server thresholds ────────────────────────────────────

    @Test fun spinning_at_server_warning_threshold_is_warn() {
        // WHY: Unraid lets admins configure tighter disk-level thresholds. When
        // warningC=45 and tempC=46 the server threshold fires even though the
        // app fallback (42) would also fire — the server value takes precedence
        // and the result should be Warn (not Danger, since criticalC=55).
        val disk = disk(isSpinning = true, tempC = 46, warningC = 45, criticalC = 55)
        assertEquals(DiskTempLevel.Warn, disk.tempLevel())
    }

    @Test fun spinning_at_server_critical_threshold_is_danger() {
        // WHY: criticalC takes precedence over warningC.
        val disk = disk(isSpinning = true, tempC = 55, warningC = 45, criticalC = 55)
        assertEquals(DiskTempLevel.Danger, disk.tempLevel())
    }

    @Test fun spinning_below_server_warning_threshold_is_normal() {
        // WHY: the server threshold is tighter than the fallback; a disk at 41 °C
        // with warningC=45 must be Normal (the app default of 42 must not fire
        // when a per-disk threshold is present).
        val disk = disk(isSpinning = true, tempC = 41, warningC = 45, criticalC = 55)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel())
    }

    @Test fun server_critical_only_no_warning_threshold() {
        // WHY: criticalC can be set without warningC; below critical → Normal.
        val disk = disk(isSpinning = true, tempC = 49, warningC = null, criticalC = 50)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel())
    }

    // ── Builder ───────────────────────────────────────────────────────

    private fun disk(
        isSpinning: Boolean,
        tempC: Int,
        warningC: Int? = null,
        criticalC: Int? = null,
    ) = Disk(
        name = "disk1",
        device = "sda",
        type = DiskType.Data,
        sizeTb = 4.0,
        usedTb = 2.0,
        tempC = tempC,
        status = DiskStatus.Ok,
        model = "",
        isSpinning = isSpinning,
        rotational = true,
        numErrors = 0L,
        warningC = warningC,
        criticalC = criticalC,
    )
}
