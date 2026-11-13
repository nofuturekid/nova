package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.AttentionSeverity
import io.github.nofuturekid.nova.data.model.AttentionTarget
import io.github.nofuturekid.nova.data.model.Disk
import io.github.nofuturekid.nova.data.model.DiskStatus
import io.github.nofuturekid.nova.data.model.DiskType
import io.github.nofuturekid.nova.data.model.NotifImportance
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.UnraidNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [evaluateAlerts], the decision behind alerts outside the app.
 *
 * WHY these cases matter: an alert that repeats teaches users to switch alerts off, and
 * one that never comes defeats the feature. So:
 * - turning alerts on must not dump what is already unread (first check seeds silently);
 * - a notification is reported once, however often it is read and even after it left the
 *   unread list, but a later notification with the same text is a new event;
 * - an array that stays stopped is one alert, not one per check, and a new episode after
 *   it was fine in between is an alert again;
 * - a server that did not answer is not news, and must not cause a repeat once it does;
 * - the state must not grow without bound, because it lives on the phone for good.
 */
class AlertRulesTest {

    private val tower = ServerRef("srv-1", "Tower")
    private val both = AlertLevel.AlertsAndWarnings

    // ── First check ───────────────────────────────────────────────────

    @Test fun first_check_posts_nothing_and_records_baseline() {
        // WHY: switching alerts on must not flood the user with everything already unread.
        val result = evaluateAlerts(
            tower, ServerAlertState(),
            notifications = unread(alert("n1"), warning("n2")),
            array = array(ArrayState.Stopped),
            level = both,
        )
        assertEquals(emptyList<Alert>(), result.toPost)
        assertEquals(0, result.foldedCount)
        assertTrue(result.nextState.notificationsBaselined)
        assertTrue(result.nextState.conditionsBaselined)
        assertEquals(listOf("n1", "n2"), result.nextState.seenNotificationIds)
        assertEquals(setOf("array-stopped"), result.nextState.openConditions)
    }

    @Test fun after_the_baseline_only_what_is_new_is_posted() {
        val first = evaluateAlerts(tower, ServerAlertState(), unread(alert("n1")), array(ArrayState.Started), both)
        val second = evaluateAlerts(tower, first.nextState, unread(alert("n1"), alert("n2", subject = "Disk 3 failed")), array(ArrayState.Started), both)
        assertEquals(listOf("n2"), second.toPost.map { it.key })
        assertEquals("Disk 3 failed", second.toPost.single().title)
    }

    // ── Notifications: once per id ────────────────────────────────────

    @Test fun new_id_is_posted_once_and_never_again_even_after_leaving_unread() {
        // WHY: "never re-alert the same notification", also when it is archived and comes back.
        var state = evaluateAlerts(tower, ServerAlertState(), unread(), array(ArrayState.Started), both).nextState
        val appears = evaluateAlerts(tower, state, unread(alert("n1")), null, both)
        assertEquals(listOf("n1"), appears.toPost.map { it.key })
        state = appears.nextState

        val stillThere = evaluateAlerts(tower, state, unread(alert("n1")), null, both)
        assertEquals(emptyList<Alert>(), stillThere.toPost)
        state = stillThere.nextState

        val archived = evaluateAlerts(tower, state, unread(), null, both)
        assertEquals(emptyList<Alert>(), archived.toPost)
        state = archived.nextState

        val comesBack = evaluateAlerts(tower, state, unread(alert("n1")), null, both)
        assertEquals(emptyList<Alert>(), comesBack.toPost)
    }

    @Test fun same_text_with_a_new_id_is_a_new_event() {
        // WHY: Unraid builds the id from the event time, so the same problem a day later is a
        // new notification and must be reported again.
        val state = baselined(seen = listOf("disk-hot-100"))
        val result = evaluateAlerts(tower, state, unread(warning("disk-hot-200", subject = "disk1 is hot")), null, both)
        assertEquals(listOf("disk-hot-200"), result.toPost.map { it.key })
    }

    @Test fun info_is_never_posted() {
        // WHY: INFO includes "returned to normal" and update notices; they are not alerts.
        val state = baselined()
        val result = evaluateAlerts(tower, state, unread(info("i1")), null, both)
        assertEquals(emptyList<Alert>(), result.toPost)
        // It is remembered all the same, so it cannot come back as news.
        assertEquals(listOf("i1"), result.nextState.seenNotificationIds)
    }

    @Test fun alert_and_warning_map_to_critical_and_warning_with_their_text() {
        val result = evaluateAlerts(
            tower, baselined(),
            unread(alert("a", subject = "Disk 1 failed", description = "Disk 1 is in error state", timestamp = "2026-11-13T10:00:00Z"), warning("w")),
            null, both,
        )
        val a = result.toPost.first { it.key == "a" }
        assertEquals(AttentionSeverity.Critical, a.severity)
        assertEquals("Disk 1 failed", a.title)
        assertEquals("Disk 1 is in error state", a.text)
        assertEquals("2026-11-13T10:00:00Z", a.timestamp)
        assertEquals(AlertSource.Notification, a.source)
        assertEquals(AttentionTarget.Notifications, a.target)
        assertEquals(AttentionSeverity.Warning, result.toPost.first { it.key == "w" }.severity)
    }

    @Test fun a_blank_subject_falls_back_to_the_title() {
        val result = evaluateAlerts(tower, baselined(), unread(alert("a", subject = "", title = "Array notice")), null, both)
        assertEquals("Array notice", result.toPost.single().title)
    }

    // ── Level ─────────────────────────────────────────────────────────

    @Test fun alerts_only_drops_warnings_but_marks_them_seen() {
        // WHY: switching from "Alerts only" to "Alerts and warnings" later must not post old warnings.
        val state = baselined()
        val quiet = evaluateAlerts(tower, state, unread(warning("w1"), alert("a1")), null, AlertLevel.AlertsOnly)
        assertEquals(listOf("a1"), quiet.toPost.map { it.key })
        assertTrue("w1" in quiet.nextState.seenNotificationIds)

        val widened = evaluateAlerts(tower, quiet.nextState, unread(warning("w1"), alert("a1")), null, both)
        assertEquals(emptyList<Alert>(), widened.toPost)
    }

    @Test fun alerts_and_warnings_posts_both() {
        val result = evaluateAlerts(tower, baselined(), unread(warning("w1"), alert("a1")), null, both)
        assertEquals(setOf("w1", "a1"), result.toPost.map { it.key }.toSet())
    }

    @Test fun alerts_only_still_posts_an_array_that_is_not_started() {
        // WHY: Unraid never notifies about a stopped array; the level only quiets Unraid's own warnings.
        val stopped = evaluateAlerts(tower, baselined(), null, array(ArrayState.Stopped), AlertLevel.AlertsOnly)
        assertEquals(listOf("array-stopped"), stopped.toPost.map { it.key })

        val degraded = evaluateAlerts(tower, baselined(), null, array(ArrayState.Error), AlertLevel.AlertsOnly)
        assertEquals(listOf("array-degraded"), degraded.toPost.map { it.key })
    }

    // ── Array conditions ──────────────────────────────────────────────

    @Test fun array_stopped_posted_on_transition_not_while_it_lasts_and_rearmed_after_clearing() {
        // WHY: one alert per episode. A server that stays stopped all night is one alert.
        var state = evaluateAlerts(tower, ServerAlertState(), null, array(ArrayState.Started), both).nextState

        val begins = evaluateAlerts(tower, state, null, array(ArrayState.Stopped), both)
        val alert = begins.toPost.single()
        assertEquals("array-stopped", alert.key)
        assertEquals(AlertSource.ArrayCondition, alert.source)
        assertEquals(AttentionTarget.Array, alert.target)
        assertEquals("Array is not started", alert.title)
        state = begins.nextState

        repeat(3) {
            val lasts = evaluateAlerts(tower, state, null, array(ArrayState.Stopped), both)
            assertEquals(emptyList<Alert>(), lasts.toPost)
            state = lasts.nextState
        }

        state = evaluateAlerts(tower, state, null, array(ArrayState.Started), both).nextState
        assertEquals(emptySet<String>(), state.openConditions)

        val again = evaluateAlerts(tower, state, null, array(ArrayState.Stopped), both)
        assertEquals(listOf("array-stopped"), again.toPost.map { it.key })
    }

    @Test fun degraded_array_is_critical_and_raised_once() {
        val state = baselined()
        val begins = evaluateAlerts(tower, state, null, array(ArrayState.Error), AlertLevel.AlertsOnly)
        val alert = begins.toPost.single()
        assertEquals("array-degraded", alert.key)
        assertEquals(AttentionSeverity.Critical, alert.severity)
        assertEquals(emptyList<Alert>(), evaluateAlerts(tower, begins.nextState, null, array(ArrayState.Error), AlertLevel.AlertsOnly).toPost)
    }

    @Test fun disk_temperature_and_disk_error_items_are_not_posted() {
        // WHY: Unraid already notifies about hot disks and disk errors. Posting them from the
        // array as well would double every alert. This fails if STATE_ALERT_KEYS is widened
        // without deciding that.
        assertEquals(setOf("array-stopped", "array-degraded"), STATE_ALERT_KEYS)
        val sick = array(ArrayState.Started, disks = listOf(disk("disk1", tempC = 70, numErrors = 12)))
        val result = evaluateAlerts(tower, baselined(), null, sick, both)
        assertEquals(emptyList<Alert>(), result.toPost)
        assertEquals(emptySet<String>(), result.nextState.openConditions)
    }

    @Test fun rebuilding_array_is_not_posted() {
        val result = evaluateAlerts(tower, baselined(), null, array(ArrayState.Parity), both)
        assertEquals(emptyList<Alert>(), result.toPost)
    }

    // ── Failures ──────────────────────────────────────────────────────

    @Test fun unreachable_server_is_silent_and_changes_nothing() {
        // WHY: nothing was loaded; "the server did not answer" is not an alert, and it must not
        // reset the memory, or the next successful check would repeat everything.
        val state = ServerAlertState(true, listOf("n1"), true, setOf("array-stopped"))
        val result = evaluateAlerts(tower, state, notifications = null, array = null, level = both)
        assertEquals(emptyList<Alert>(), result.toPost)
        assertEquals(0, result.foldedCount)
        assertEquals(state, result.nextState)
    }

    @Test fun unreachable_before_the_first_success_does_not_baseline() {
        // WHY: a failed check must not count as the silent first check, or an alert that
        // appeared meanwhile would be swallowed.
        val result = evaluateAlerts(tower, ServerAlertState(), null, null, both)
        assertEquals(ServerAlertState(), result.nextState)
    }

    @Test fun failed_notifications_keep_that_half_and_still_evaluate_the_array() {
        val state = ServerAlertState(true, listOf("n1"), true, emptySet())
        val result = evaluateAlerts(tower, state, notifications = null, array = array(ArrayState.Stopped), level = both)
        assertEquals(listOf("array-stopped"), result.toPost.map { it.key })
        assertEquals(listOf("n1"), result.nextState.seenNotificationIds)
    }

    @Test fun failed_array_keeps_the_condition_half_and_still_evaluates_notifications() {
        val state = ServerAlertState(true, listOf("n1"), true, setOf("array-stopped"))
        val result = evaluateAlerts(tower, state, unread(alert("n2")), array = null, level = both)
        assertEquals(listOf("n2"), result.toPost.map { it.key })
        assertEquals(setOf("array-stopped"), result.nextState.openConditions)
    }

    @Test fun items_created_during_an_outage_are_posted_once_when_the_server_answers_again() {
        var state = baselined(seen = listOf("n1"))
        state = evaluateAlerts(tower, state, null, null, both).nextState
        val back = evaluateAlerts(tower, state, unread(alert("n1"), alert("n2")), null, both)
        assertEquals(listOf("n2"), back.toPost.map { it.key })
        assertEquals(emptyList<Alert>(), evaluateAlerts(tower, back.nextState, unread(alert("n1"), alert("n2")), null, both).toPost)
    }

    @Test fun every_probe_result_but_ok_loads_nothing() {
        // WHY: failures must travel the "nothing loaded" path, whichever way they failed.
        val failures = listOf(
            ProbeResult.Unreachable, ProbeResult.CertificateProblem, ProbeResult.KeyMissing,
            ProbeResult.KeyUndecryptable, ProbeResult.Rejected("HTTP 401"), ProbeResult.NotEncrypted,
        )
        for (failure in failures) {
            val state = ServerAlertState(true, listOf("n1"), true, setOf("array-degraded"))
            val result = evaluateAlerts(tower, state, failure.loadedNotifications, failure.loadedArray, both)
            assertEquals("$failure", emptyList<Alert>(), result.toPost)
            assertEquals("$failure", state, result.nextState)
        }
        val ok = ProbeResult.Ok(unread(alert("n9")), array(ArrayState.Started))
        assertEquals(listOf("n9"), ok.loadedNotifications!!.items.map { it.id })
        assertEquals(ArrayState.Started, ok.loadedArray!!.state)
    }

    // ── Flood cap ─────────────────────────────────────────────────────

    @Test fun more_than_five_new_fold_into_a_summary_and_are_all_remembered() {
        val state = baselined()
        val many = (1..8).map { alert("n$it") }
        val result = evaluateAlerts(tower, state, unread(*many.toTypedArray()), null, both)
        assertEquals(MAX_ALERTS_PER_CHECK, result.toPost.size)
        assertEquals(3, result.foldedCount)
        assertEquals("and 3 more on Tower", foldedSummaryText(tower.name, result.foldedCount))
        // WHY: folded ones were announced by the summary; they must not return next time.
        assertEquals((1..8).map { "n$it" }, result.nextState.seenNotificationIds)
        assertEquals(emptyList<Alert>(), evaluateAlerts(tower, result.nextState, unread(*many.toTypedArray()), null, both).toPost)
    }

    @Test fun the_most_urgent_alerts_get_the_individual_notifications() {
        val state = baselined()
        val items = (1..6).map { warning("w$it") } + alert("a1") + alert("a2")
        val result = evaluateAlerts(tower, state, unread(*items.toTypedArray()), array(ArrayState.Error), both)
        assertEquals(5, result.toPost.size)
        assertEquals(4, result.foldedCount)
        // Critical first: the degraded array and both alerts, then warnings.
        assertEquals(setOf("array-degraded", "a1", "a2"), result.toPost.take(3).map { it.key }.toSet())
        assertTrue(result.toPost.drop(3).all { it.severity == AttentionSeverity.Warning })
    }

    @Test fun five_or_fewer_are_not_folded() {
        val result = evaluateAlerts(tower, baselined(), unread(*(1..5).map { alert("n$it") }.toTypedArray()), null, both)
        assertEquals(5, result.toPost.size)
        assertEquals(0, result.foldedCount)
    }

    // ── Bounded memory ────────────────────────────────────────────────

    @Test fun seen_ids_are_capped_and_the_oldest_are_forgotten_first() {
        // WHY: the state lives on the phone for good; without a cap it grows with every
        // notification the server ever shows.
        val old = (1..MAX_SEEN_NOTIFICATION_IDS).map { "old$it" }
        val state = baselined(seen = old)
        val result = evaluateAlerts(tower, state, unread(alert("new1"), alert("new2")), null, both)
        val seen = result.nextState.seenNotificationIds
        assertEquals(MAX_SEEN_NOTIFICATION_IDS, seen.size)
        assertFalse("old1" in seen)
        assertFalse("old2" in seen)
        assertEquals("old3", seen.first())
        assertEquals(listOf("new1", "new2"), seen.takeLast(2))
    }

    @Test fun ids_that_are_gone_from_the_server_are_forgotten_before_ids_still_listed() {
        // WHY: an id still on the server must never be forgotten, or it would be posted again.
        val state = baselined(seen = listOf("still") + (1..MAX_SEEN_NOTIFICATION_IDS - 1).map { "gone$it" })
        val result = evaluateAlerts(tower, state, unread(alert("still"), alert("fresh")), null, both)
        val seen = result.nextState.seenNotificationIds
        assertEquals(MAX_SEEN_NOTIFICATION_IDS, seen.size)
        assertTrue("still" in seen)
        assertFalse("gone1" in seen)
        assertEquals(emptyList<Alert>(), evaluateAlerts(tower, result.nextState, unread(alert("still"), alert("fresh")), null, both).toPost)
    }

    @Test fun a_repeated_id_in_one_read_is_remembered_once() {
        val result = evaluateAlerts(tower, baselined(), unread(alert("n1"), alert("n1")), null, both)
        assertEquals(1, result.toPost.size)
        assertEquals(listOf("n1"), result.nextState.seenNotificationIds)
    }

    @Test fun an_unknown_array_state_keeps_the_condition_half() {
        // WHY: Offline means "not known" (no array block, unknown enum value), not "condition gone";
        // clearing the open condition would post the same stopped array twice in one episode.
        val unknown = evaluateAlerts(tower, baselined(open = setOf("array-stopped")), null, array(ArrayState.Offline), both)
        assertEquals(emptyList<Alert>(), unknown.toPost)
        assertEquals(setOf("array-stopped"), unknown.nextState.openConditions)
        assertEquals(emptyList<Alert>(), evaluateAlerts(tower, unknown.nextState, null, array(ArrayState.Stopped), both).toPost)

        // A first read that is unknown must not count as the silent baseline.
        assertFalse(evaluateAlerts(tower, ServerAlertState(), null, array(ArrayState.Offline), both).nextState.conditionsBaselined)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun baselined(seen: List<String> = emptyList(), open: Set<String> = emptySet()) =
        ServerAlertState(true, seen, true, open)

    private fun unread(vararg items: UnraidNotification) = Notifications(
        unreadWarning = items.count { it.importance == NotifImportance.Warning },
        unreadAlert = items.count { it.importance == NotifImportance.Alert },
        unreadInfo = items.count { it.importance == NotifImportance.Info },
        unreadTotal = items.size,
        items = items.toList(),
    )

    private fun alert(id: String, subject: String = "Alert $id", title: String = "Title $id", description: String = "Details $id", timestamp: String? = null) =
        notification(id, NotifImportance.Alert, subject, title, description, timestamp)

    private fun warning(id: String, subject: String = "Warning $id") =
        notification(id, NotifImportance.Warning, subject, "Title $id", "Details $id", null)

    private fun info(id: String) = notification(id, NotifImportance.Info, "Info $id", "Title $id", "Details $id", null)

    private fun notification(id: String, importance: NotifImportance, subject: String, title: String, description: String, timestamp: String?) =
        UnraidNotification(
            id = id, title = title, subject = subject, description = description, importance = importance,
            type = null, link = null, timestamp = timestamp, formattedTimestamp = null,
        )

    private fun array(state: ArrayState, disks: List<Disk> = emptyList()) =
        ArrayInfo(state, totalTb = 8.0, usedTb = 2.0, parity = null, disks = disks)

    private fun disk(name: String, tempC: Int, numErrors: Long = 0L) = Disk(
        name = name, device = "sdb", type = DiskType.Data, sizeTb = 4.0, usedTb = 1.0,
        tempC = tempC, status = DiskStatus.Ok, model = "", isSpinning = true, rotational = true,
        numErrors = numErrors, warningC = 45, criticalC = 55,
    )
}
