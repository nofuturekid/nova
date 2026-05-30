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
 * - Global display thresholds (from `display.hot` / `display.max`) prevent false
 *   alarms: a 52 °C disk with global crit=60 must show Normal, not Danger vs the
 *   hardcoded 50 °C fallback. This is the beta9 fix (ADR-0045 updated).
 * - Per threshold, coalescing is independent: per-disk value if set, else global,
 *   else hardcoded last-resort (42 warn / 50 crit). The beta8 "if EITHER per-disk
 *   set, ignore the other" rule is removed.
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

    @Test fun spinning_false_is_standby_with_global_thresholds() {
        // WHY: global thresholds don't affect the standby branch.
        val disk = disk(isSpinning = false, tempC = 80)
        assertEquals(DiskTempLevel.Standby, disk.tempLevel(globalWarnC = 55, globalCritC = 60))
    }

    // ── Global display thresholds (the beta9 false-alarm fix) ─────────

    @Test fun global_thresholds_prevent_false_danger_at_52() {
        // WHY: the primary beta9 bug fix. A 52 °C disk with global crit=60 was
        // wrongly shown as Danger because the hardcoded 50 °C fallback fired.
        // With global thresholds threaded in, 52 < 55 (warn) → Normal.
        val disk = disk(isSpinning = true, tempC = 52)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel(globalWarnC = 55, globalCritC = 60))
    }

    @Test fun global_thresholds_warn_at_56() {
        // WHY: 56 >= 55 (global warn) → Warn; below crit (60) → not Danger.
        val disk = disk(isSpinning = true, tempC = 56)
        assertEquals(DiskTempLevel.Warn, disk.tempLevel(globalWarnC = 55, globalCritC = 60))
    }

    @Test fun global_thresholds_danger_at_61() {
        // WHY: 61 >= 60 (global crit) → Danger.
        val disk = disk(isSpinning = true, tempC = 61)
        assertEquals(DiskTempLevel.Danger, disk.tempLevel(globalWarnC = 55, globalCritC = 60))
    }

    @Test fun global_thresholds_danger_at_exactly_60() {
        // WHY: threshold is inclusive (>=).
        val disk = disk(isSpinning = true, tempC = 60)
        assertEquals(DiskTempLevel.Danger, disk.tempLevel(globalWarnC = 55, globalCritC = 60))
    }

    // ── Hardcoded fallback (no per-disk, no global) ───────────────────

    @Test fun spinning_cold_with_null_thresholds_is_normal() {
        // WHY: a healthy cool spinning disk with no thresholds configured must
        // report Normal — no false alarms.
        val disk = disk(isSpinning = true, tempC = 39)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel())
    }

    @Test fun spinning_just_below_fallback_warn_is_normal() {
        // WHY: 41 < 42 fallback warn threshold → Normal.
        val disk = disk(isSpinning = true, tempC = 41)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel())
    }

    @Test fun spinning_at_fallback_warn_threshold_is_warn() {
        // WHY: 42 °C is the app's default warning level. At exactly 42 with no
        // thresholds configured anywhere, the result must be Warn, not Normal.
        val disk = disk(isSpinning = true, tempC = 42)
        assertEquals(DiskTempLevel.Warn, disk.tempLevel())
    }

    @Test fun spinning_at_fallback_danger_threshold_is_danger() {
        // WHY: 50 °C is the app's default critical level (inclusive).
        val disk = disk(isSpinning = true, tempC = 50)
        assertEquals(DiskTempLevel.Danger, disk.tempLevel())
    }

    @Test fun spinning_above_fallback_danger_threshold_is_danger() {
        // WHY: null global + null per-disk → fallback 50 crit. 52 > 50 → Danger.
        val disk = disk(isSpinning = true, tempC = 52)
        assertEquals(DiskTempLevel.Danger, disk.tempLevel())
    }

    // ── Per-disk thresholds + global coalescing ───────────────────────

    @Test fun per_disk_crit_only_no_warn_uses_global_warn() {
        // WHY: per-disk criticalC=50, no per-disk warningC, global(null,null).
        // effWarn = null ?: null ?: 42 = 42.  49 >= 42 → Warn.
        val disk = disk(isSpinning = true, tempC = 49, criticalC = 50)
        assertEquals(DiskTempLevel.Warn, disk.tempLevel(globalWarnC = null, globalCritC = null))
    }

    @Test fun per_disk_crit_only_global_warn_55_normal_at_49() {
        // WHY: per-disk criticalC=50, no per-disk warningC, global warn=55.
        // effWarn = null ?: 55 = 55.  effCrit = 50 ?: null = 50.
        // 49 < 50 (effCrit) and 49 < 55 (effWarn) → Normal.
        val disk = disk(isSpinning = true, tempC = 49, criticalC = 50)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel(globalWarnC = 55, globalCritC = 60))
    }

    @Test fun per_disk_warn_wins_over_global() {
        // WHY: per-disk warningC=45 is tighter than global warn=55; 46 >= 45 → Warn.
        val disk = disk(isSpinning = true, tempC = 46, warningC = 45)
        assertEquals(DiskTempLevel.Warn, disk.tempLevel(globalWarnC = 55, globalCritC = 60))
    }

    @Test fun spinning_at_server_warning_threshold_is_warn() {
        // WHY: warningC=45, criticalC=55; 46 >= 45 → Warn (not Danger).
        val disk = disk(isSpinning = true, tempC = 46, warningC = 45, criticalC = 55)
        assertEquals(DiskTempLevel.Warn, disk.tempLevel())
    }

    @Test fun spinning_at_server_critical_threshold_is_danger() {
        // WHY: criticalC=55 takes precedence over warningC=45.
        val disk = disk(isSpinning = true, tempC = 55, warningC = 45, criticalC = 55)
        assertEquals(DiskTempLevel.Danger, disk.tempLevel())
    }

    @Test fun spinning_below_server_warning_threshold_is_normal() {
        // WHY: warningC=45 is tighter than fallback (42); 41 < 45 → Normal.
        val disk = disk(isSpinning = true, tempC = 41, warningC = 45, criticalC = 55)
        assertEquals(DiskTempLevel.Normal, disk.tempLevel())
    }

    @Test fun server_critical_only_no_warning_threshold_no_global() {
        // WHY: criticalC=50, no per-disk warn, no global → effWarn=42. 49 >= 42 → Warn.
        val disk = disk(isSpinning = true, tempC = 49, criticalC = 50)
        assertEquals(DiskTempLevel.Warn, disk.tempLevel())
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
