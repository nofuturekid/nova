package io.github.nofuturekid.nova.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [attentionItems], the one rule set behind the Overview card and later
 * the background alerts and the widget.
 *
 * WHY these cases matter:
 * - A healthy server must yield an empty list, or the card would cry wolf and
 *   users would stop reading it.
 * - Unknown is not a problem: an array that is not loaded, a spun-down disk, a
 *   threshold the server does not report or an error count the API does not fill
 *   must never raise anything.
 * - Keys name the condition, not the moment, so a later alert can avoid repeating
 *   itself while the same problem lasts, yet still fire when it gets worse in kind.
 * - The order is by urgency, so the most important row is always on top.
 */
class AttentionItemsTest {

    // ── Healthy and unknown ───────────────────────────────────────────

    @Test fun healthy_server_has_nothing_to_flag() {
        val snap = snapshot(
            array = array(disks = listOf(disk("disk1", tempC = 35), disk("parity", tempC = 33))),
            notifications = notifications(),
            thresholds = DisplayThresholds(45, 55, 70, 90),
        )
        assertEquals(emptyList<AttentionItem>(), attentionItems(snap))
    }

    @Test fun nothing_loaded_has_nothing_to_flag() {
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot()))
    }

    @Test fun array_state_offline_means_unknown_not_a_problem() {
        // WHY: the mapper uses Offline for "no array data"; it must not alarm.
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = array(state = ArrayState.Offline))))
    }

    // ── Array state ───────────────────────────────────────────────────

    @Test fun stopped_array_is_a_warning() {
        val item = attentionItems(snapshot(array = array(state = ArrayState.Stopped))).single()
        assertEquals("array-stopped", item.key)
        assertEquals(AttentionSeverity.Warning, item.severity)
        assertEquals(AttentionTarget.Array, item.target)
    }

    @Test fun degraded_array_is_critical() {
        val item = attentionItems(snapshot(array = array(state = ArrayState.Error))).single()
        assertEquals("array-degraded", item.key)
        assertEquals(AttentionSeverity.Critical, item.severity)
    }

    @Test fun rebuilding_state_without_an_operation_is_flagged_once() {
        val item = attentionItems(snapshot(array = array(state = ArrayState.Parity))).single()
        assertEquals("array-rebuilding", item.key)
    }

    @Test fun rebuilding_state_with_a_running_operation_shows_only_the_operation() {
        // WHY: the state and the operation describe the same thing; two rows would double the noise.
        val keys = attentionItems(snapshot(array = array(state = ArrayState.Parity, parity = ParityCheck(0.3f, 100.0, false)))).map { it.key }
        assertEquals(listOf("parity-running"), keys)
    }

    // ── Parity ────────────────────────────────────────────────────────

    @Test fun running_parity_operation_is_info_and_keeps_its_key_as_progress_moves() {
        // WHY: progress changes every poll; the key must not, or a later alert would repeat.
        val early = attentionItems(snapshot(array = array(parity = ParityCheck(0.10f, 100.0, false)))).single()
        val late = attentionItems(snapshot(array = array(parity = ParityCheck(0.85f, 100.0, false)))).single()
        assertEquals(AttentionSeverity.Info, early.severity)
        assertEquals(early.key, late.key)
        assertEquals("10% done.", early.detail)
        assertEquals("85% done.", late.detail)
    }

    @Test fun paused_parity_operation_is_a_warning_with_its_own_key() {
        val item = attentionItems(snapshot(array = array(parity = ParityCheck(0.5f, 0.0, true)))).single()
        assertEquals("parity-paused", item.key)
        assertEquals(AttentionSeverity.Warning, item.severity)
    }

    @Test fun failed_last_check_is_critical() {
        val item = attentionItems(snapshot(array = array(lastRun = ParityLastRun(ParityOutcome.Failed, null)))).single()
        assertEquals("parity-failed", item.key)
        assertEquals(AttentionSeverity.Critical, item.severity)
    }

    @Test fun completed_check_with_errors_is_critical() {
        val item = attentionItems(snapshot(array = array(lastRun = ParityLastRun(ParityOutcome.Completed, 3)))).single()
        assertEquals("parity-errors", item.key)
        assertEquals(AttentionSeverity.Critical, item.severity)
        assertEquals("3 errors in the last check.", item.detail)
    }

    @Test fun completed_check_with_one_error_uses_the_singular() {
        val item = attentionItems(snapshot(array = array(lastRun = ParityLastRun(ParityOutcome.Completed, 1)))).single()
        assertEquals("1 error in the last check.", item.detail)
    }

    @Test fun completed_check_without_errors_or_with_unknown_errors_is_calm() {
        // WHY: the API does not fill the error count today; unknown must never read as "errors".
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = array(lastRun = ParityLastRun(ParityOutcome.Completed, 0)))))
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = array(lastRun = ParityLastRun(ParityOutcome.Completed, null)))))
    }

    @Test fun cancelled_check_is_the_users_own_doing() {
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = array(lastRun = ParityLastRun(ParityOutcome.Cancelled, null)))))
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = array(lastRun = ParityLastRun(ParityOutcome.Cancelled, 5)))))
    }

    // ── Disk errors ───────────────────────────────────────────────────

    @Test fun disk_with_errors_is_critical_and_keyed_per_disk() {
        val items = attentionItems(snapshot(array = array(disks = listOf(disk("disk1", numErrors = 12), disk("disk2", numErrors = 1), disk("disk3")))))
        assertEquals(listOf("disk-errors:disk1", "disk-errors:disk2"), items.map { it.key })
        assertTrue(items.all { it.severity == AttentionSeverity.Critical })
        assertEquals("12 errors reported.", items[0].detail)
        assertEquals("1 error reported.", items[1].detail)
    }

    @Test fun error_count_changes_do_not_change_the_key() {
        val a = attentionItems(snapshot(array = array(disks = listOf(disk("disk1", numErrors = 2))))).single()
        val b = attentionItems(snapshot(array = array(disks = listOf(disk("disk1", numErrors = 50))))).single()
        assertEquals(a.key, b.key)
    }

    @Test fun disk_without_a_name_is_identified_by_its_device() {
        val item = attentionItems(snapshot(array = array(disks = listOf(disk(name = "", device = "sdb", numErrors = 1))))).single()
        assertEquals("disk-errors:sdb", item.key)
    }

    // ── Disk temperature ──────────────────────────────────────────────

    @Test fun disk_at_the_global_warning_threshold_is_a_warning() {
        val snap = snapshot(array = array(disks = listOf(disk("disk1", tempC = 45))), thresholds = DisplayThresholds(45, 55, null, null))
        val item = attentionItems(snap).single()
        assertEquals("disk-temp-warning:disk1", item.key)
        assertEquals(AttentionSeverity.Warning, item.severity)
        assertEquals("45 °C, warning from 45 °C.", item.detail)
    }

    @Test fun disk_at_the_global_critical_threshold_is_critical() {
        val snap = snapshot(array = array(disks = listOf(disk("disk1", tempC = 55))), thresholds = DisplayThresholds(45, 55, null, null))
        val item = attentionItems(snap).single()
        assertEquals("disk-temp-critical:disk1", item.key)
        assertEquals(AttentionSeverity.Critical, item.severity)
    }

    @Test fun warm_to_hot_is_a_new_condition_with_a_new_key() {
        // WHY: a disk that goes from warm to critical is worse in kind and should be able to alert again.
        val thresholds = DisplayThresholds(45, 55, null, null)
        val warm = attentionItems(snapshot(array = array(disks = listOf(disk("disk1", tempC = 47))), thresholds = thresholds)).single()
        val hot = attentionItems(snapshot(array = array(disks = listOf(disk("disk1", tempC = 58))), thresholds = thresholds)).single()
        assertTrue(warm.key != hot.key)
    }

    @Test fun temperature_inside_the_same_level_keeps_its_key() {
        val thresholds = DisplayThresholds(45, 55, null, null)
        val a = attentionItems(snapshot(array = array(disks = listOf(disk("disk1", tempC = 46))), thresholds = thresholds)).single()
        val b = attentionItems(snapshot(array = array(disks = listOf(disk("disk1", tempC = 49))), thresholds = thresholds)).single()
        assertEquals(a.key, b.key)
    }

    @Test fun per_disk_threshold_beats_the_global_one() {
        // WHY: a disk the user gave its own limit must follow it, in both directions.
        val thresholds = DisplayThresholds(45, 55, null, null)
        val lenient = disk("disk1", tempC = 50, warningC = 60, criticalC = 70)
        val strict = disk("disk2", tempC = 40, warningC = 38, criticalC = 70)
        val keys = attentionItems(snapshot(array = array(disks = listOf(lenient, strict)), thresholds = thresholds)).map { it.key }
        assertEquals(listOf("disk-temp-warning:disk2"), keys)
    }

    @Test fun thresholds_are_coalesced_independently() {
        // WHY: only a critical limit per disk, warning from the global setting.
        val snap = snapshot(array = array(disks = listOf(disk("disk1", tempC = 48, criticalC = 60))), thresholds = DisplayThresholds(45, 55, null, null))
        assertEquals("disk-temp-warning:disk1", attentionItems(snap).single().key)
    }

    @Test fun no_reported_threshold_means_no_temperature_alert() {
        // WHY: the Array tab falls back to 42/50 degrees, but this card never guesses a limit
        // the server did not configure; a hot disk without any threshold is not raised.
        val hot = array(disks = listOf(disk("disk1", tempC = 80)))
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = hot, thresholds = null)))
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = hot, thresholds = DisplayThresholds(null, null, null, null))))
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = hot, thresholds = DisplayThresholds(0, 0, null, null))))
    }

    @Test fun only_a_critical_limit_known_still_raises_critical_but_not_warning() {
        val thresholds = DisplayThresholds(null, 55, null, null)
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(array = array(disks = listOf(disk("d", tempC = 50))), thresholds = thresholds)))
        assertEquals("disk-temp-critical:d", attentionItems(snapshot(array = array(disks = listOf(disk("d", tempC = 56))), thresholds = thresholds)).single().key)
    }

    @Test fun spun_down_disk_is_never_a_temperature_problem() {
        // WHY: a sleeping disk reports no temperature; the mapper stores 0, and isSpinning is the truth.
        val snap = snapshot(
            array = array(disks = listOf(disk("disk1", tempC = 80, isSpinning = false))),
            thresholds = DisplayThresholds(45, 55, null, null),
        )
        assertEquals(emptyList<AttentionItem>(), attentionItems(snap))
    }

    @Test fun spun_down_disk_with_errors_still_counts_for_errors() {
        val snap = snapshot(array = array(disks = listOf(disk("disk1", isSpinning = false, numErrors = 4))))
        assertEquals(listOf("disk-errors:disk1"), attentionItems(snap).map { it.key })
    }

    // ── Notifications ─────────────────────────────────────────────────

    @Test fun unread_alerts_and_warnings_are_counted_and_open_the_notifications() {
        val items = attentionItems(snapshot(notifications = notifications(warning = 1, alert = 2)))
        assertEquals(listOf("notifications-alert", "notifications-warning"), items.map { it.key })
        assertEquals(listOf("2 unread alerts", "1 unread warning"), items.map { it.title })
        assertTrue(items.all { it.target == AttentionTarget.Notifications })
    }

    @Test fun info_notifications_are_not_a_problem() {
        assertEquals(emptyList<AttentionItem>(), attentionItems(snapshot(notifications = notifications(info = 9))))
    }

    @Test fun notification_count_changes_do_not_change_the_key() {
        val a = attentionItems(snapshot(notifications = notifications(alert = 1))).single()
        val b = attentionItems(snapshot(notifications = notifications(alert = 7))).single()
        assertEquals(a.key, b.key)
    }

    // ── Ordering and identity ─────────────────────────────────────────

    @Test fun items_are_ordered_by_severity_then_by_rule_order() {
        val snap = snapshot(
            array = array(
                state = ArrayState.Stopped,                       // warning
                parity = ParityCheck(0.2f, 90.0, false),         // info
                disks = listOf(disk("disk1", numErrors = 2)),    // critical
            ),
            notifications = notifications(warning = 1, alert = 1), // warning, critical
        )
        val items = attentionItems(snap)
        assertEquals(
            listOf("disk-errors:disk1", "notifications-alert", "array-stopped", "notifications-warning", "parity-running"),
            items.map { it.key },
        )
        assertEquals(items.sortedByDescending { it.severity }.map { it.key }, items.map { it.key })
    }

    @Test fun keys_are_unique_and_stable_across_evaluations() {
        val snap = snapshot(
            array = array(state = ArrayState.Error, disks = listOf(disk("disk1", numErrors = 1), disk("disk2", numErrors = 1))),
            notifications = notifications(alert = 1, warning = 1),
        )
        val first = attentionItems(snap).map { it.key }
        assertEquals("a repeat evaluation of the same data must give the same keys", first, attentionItems(snap).map { it.key })
        assertEquals("keys identify items, so they must be unique", first.size, first.toSet().size)
    }

    // ── Builders ──────────────────────────────────────────────────────

    private fun snapshot(
        array: ArrayInfo? = null,
        notifications: Notifications? = null,
        thresholds: DisplayThresholds? = null,
    ) = AttentionSnapshot(array, notifications, thresholds)

    private fun array(
        state: ArrayState = ArrayState.Started,
        parity: ParityCheck? = null,
        lastRun: ParityLastRun? = null,
        disks: List<Disk> = emptyList(),
    ) = ArrayInfo(state, totalTb = 8.0, usedTb = 2.0, parity = parity, disks = disks, parityLastRun = lastRun)

    private fun disk(
        name: String,
        tempC: Int = 35,
        isSpinning: Boolean = true,
        numErrors: Long = 0L,
        warningC: Int? = null,
        criticalC: Int? = null,
        device: String = "sd${name.lastOrNull() ?: 'x'}",
    ) = Disk(
        name = name, device = device, type = DiskType.Data, sizeTb = 4.0, usedTb = 1.0,
        tempC = tempC, status = DiskStatus.Ok, model = "", isSpinning = isSpinning, rotational = true,
        numErrors = numErrors, warningC = warningC, criticalC = criticalC,
    )

    private fun notifications(warning: Int = 0, alert: Int = 0, info: Int = 0) = Notifications(
        unreadWarning = warning, unreadAlert = alert, unreadInfo = info,
        unreadTotal = warning + alert + info, items = emptyList(),
    )
}
