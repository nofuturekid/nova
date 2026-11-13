package io.github.nofuturekid.nova.data.alerts

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.local.SettingsStore
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.AttentionTarget
import io.github.nofuturekid.nova.data.model.NotifImportance
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Whole background checks on the real notifier (Robolectric's notification manager), the real
 * settings store and in-memory alert state, with a scripted server.
 *
 * WHY: the checks are only worth running if what a person sees on the phone is right: one
 * notification per new problem, never again for the same one, on the channel that matches how
 * serious it is, saying what Unraid said, opening the right server, and nothing at all when the
 * server cannot be reached. These tests would fail if a notification were posted twice, to the
 * wrong channel, with the server name on the lock screen, for a down server, or if one broken
 * server stopped the others from being checked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AlertCheckRunnerTest {
    private lateinit var ctx: Context
    private lateinit var settings: SettingsStore
    private lateinit var notifier: AlertNotifier
    private lateinit var nm: NotificationManager
    private val states = InMemoryAlertStateStore()
    private val probe = FakeServerProbe()
    private var workCancelled = 0

    private val tower = testServer("srv-tower", "Tower")
    private val backup = testServer("srv-backup", "Backup")

    @Before fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(ctx)
        settings.setAlertSettings(AlertSettings(enabled = true))
        settings.setServers(listOf(tower))
        notifier = AlertNotifier(ctx)
        nm = ctx.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(true)
        nm.cancelAll()
    }

    /** Settings outlive a test inside one JVM; the next test class must find alerts off. */
    @After fun tearDown() = runBlocking {
        settings.setAlertSettings(AlertSettings())
        settings.setServers(emptyList())
    }

    private fun runner(probe: ServerProbe = this.probe, timeoutMs: Long = SERVER_CHECK_TIMEOUT_MS) =
        AlertCheckRunner(settings, probe, states, notifier, { workCancelled++ }, timeoutMs)

    private fun check(probe: ServerProbe = this.probe, timeoutMs: Long = SERVER_CHECK_TIMEOUT_MS) =
        runBlocking { runner(probe, timeoutMs).run() }

    private fun posted(): List<StatusBarNotification> = nm.activeNotifications.toList()

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun Notification.subText() = extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()

    /** The first check of Tower: records what is there and posts nothing. */
    private fun baseline(vararg ids: String, state: ArrayState = ArrayState.Started) {
        probe.answer(tower.id, okResult(ids.map { unraidNotification(it) }, state))
        check()
        assertEquals("setup: the first check is silent", 0, posted().size)
    }

    @Test fun a_new_alert_posts_exactly_one_notification_with_the_right_channel_and_content() {
        baseline("n1")
        val at = "2026-11-13T10:15:30.000Z"
        probe.answer(
            tower.id,
            okResult(
                listOf(
                    unraidNotification("n1"),
                    unraidNotification("n2", subject = "Alert [Tower] - disk1 is hot (52 C)", description = "Disk 1 reached 52 C", timestamp = at),
                ),
            ),
        )

        check()

        val sbn = posted().single()
        val n = sbn.notification
        assertEquals("tag is the server id, so a server's notifications can be removed together", tower.id, sbn.tag)
        assertEquals(notificationId(tower.id, "n2"), sbn.id)
        assertEquals(ALERTS_CHANNEL_ID, n.channelId)
        assertEquals("Alert [Tower] - disk1 is hot (52 C)", n.title())
        assertEquals("Disk 1 reached 52 C", n.text())
        assertEquals("sub-text names the server", "Tower", n.subText())
        assertEquals("shows when Unraid raised it", Instant.parse(at).toEpochMilli(), n.`when`)
        assertEquals("server:${tower.id}", n.group)
        assertTrue("a tap removes it", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals("a single alert is not a group summary", 0, n.flags and Notification.FLAG_GROUP_SUMMARY)
    }

    @Test fun a_second_check_posts_nothing_for_the_same_alert() {
        // WHY: the maintainer's rule: never alert the same notification twice.
        baseline("n1")
        probe.answer(tower.id, okResult(listOf(unraidNotification("n1"), unraidNotification("n2"))))
        check()
        nm.cancelAll()

        check()
        check()

        assertEquals(0, posted().size)
    }

    @Test fun the_lock_screen_shows_neither_server_name_nor_content() {
        baseline()
        probe.answer(tower.id, okResult(listOf(unraidNotification("n1", subject = "secret subject", description = "secret detail"))))

        check()

        val n = posted().single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals("Server alert", public.title())
        assertEquals("Unlock to see details", public.text())
        assertTrue(public.extras.getCharSequence(Notification.EXTRA_SUB_TEXT) == null)
    }

    @Test fun a_tap_opens_the_app_on_that_server_and_place_through_an_immutable_intent() {
        baseline()
        probe.answer(tower.id, okResult(listOf(unraidNotification("n1")), ArrayState.Stopped))

        check()

        val byKey = posted().associateBy { it.id }
        val notificationTap = byKey.getValue(notificationId(tower.id, "n1")).notification.contentIntent
        val arrayTap = byKey.values.single { it.notification.title() == "Array is not started" }.notification.contentIntent
        listOf(notificationTap to AttentionTarget.Notifications, arrayTap to AttentionTarget.Array).forEach { (pi, target) ->
            val shadow = shadowOf(pi)
            assertTrue("immutable", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertEquals(
                NavigationRequest(tower.id, target),
                NavigationRequest.from(shadow.savedIntent),
            )
            assertTrue("single top, so a running app gets onNewIntent", shadow.savedIntent.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        }
    }

    @Test fun severity_decides_the_channel() {
        baseline(state = ArrayState.Started)
        probe.answer(
            tower.id,
            okResult(
                listOf(
                    unraidNotification("a1", NotifImportance.Alert, subject = "an alert"),
                    unraidNotification("w1", NotifImportance.Warning, subject = "a warning"),
                ),
                ArrayState.Stopped,
            ),
        )

        check()

        val channels = posted().filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .associate { it.notification.title() to it.notification.channelId }
        assertEquals(ALERTS_CHANNEL_ID, channels["an alert"])
        assertEquals(WARNINGS_CHANNEL_ID, channels["a warning"])
        assertEquals("array not started is a warning", WARNINGS_CHANNEL_ID, channels["Array is not started"])
    }

    @Test fun a_degraded_array_goes_to_the_alerts_channel() {
        baseline(state = ArrayState.Started)
        probe.answer(tower.id, okResult(emptyList(), ArrayState.Error))

        check()

        val n = posted().single().notification
        assertEquals("Array is degraded", n.title())
        assertEquals(ALERTS_CHANNEL_ID, n.channelId)
    }

    @Test fun alerts_only_posts_no_warning_but_still_remembers_it() {
        runBlocking { settings.setAlertSettings(AlertSettings(enabled = true, level = AlertLevel.AlertsOnly)) }
        baseline()
        probe.answer(tower.id, okResult(listOf(unraidNotification("w1", NotifImportance.Warning))))
        check()
        assertEquals(0, posted().size)

        runBlocking { settings.setAlertSettings(AlertSettings(enabled = true, level = AlertLevel.AlertsAndWarnings)) }
        check()

        assertEquals("the old warning is not posted when the level is widened", 0, posted().size)
    }

    @Test fun more_than_five_new_alerts_fold_into_one_summary_per_server() {
        baseline()
        probe.answer(tower.id, okResult((1..7).map { unraidNotification("n$it") }))

        check()

        val all = posted()
        val summary = all.single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.notification
        assertEquals(5, all.size - 1)
        assertEquals("and 2 more on Tower", summary.text())
        assertEquals("Tower", summary.title())
        assertEquals("server:${tower.id}", summary.group)
    }

    @Test fun an_unreachable_server_posts_nothing_and_keeps_what_it_remembered() {
        baseline("n1")
        val before = runBlocking { states.get(tower.id) }
        probe.answer(tower.id, ProbeResult.Unreachable)

        val checks = check()

        assertEquals(0, posted().size)
        assertEquals(before, runBlocking { states.get(tower.id) })
        assertEquals(ProbeResult.Unreachable, checks.single().result)
    }

    @Test fun every_failure_kind_posts_nothing() {
        baseline("n1")
        val failures = listOf(
            ProbeResult.CertificateProblem, ProbeResult.KeyMissing, ProbeResult.KeyUndecryptable, ProbeResult.Rejected("nope"),
            ProbeResult.NotEncrypted,
        )
        failures.forEach { failure ->
            probe.answer(tower.id, failure)
            check()
        }
        assertEquals(0, posted().size)
    }

    @Test fun several_servers_are_checked_independently() {
        runBlocking { settings.saveServer(listOf(tower, backup), tower.id, pin = "AA:BB", dropPin = false) }
        probe.answer(tower.id, okResult(listOf(unraidNotification("n1"))))
        probe.answer(backup.id, okResult(listOf(unraidNotification("n1"))))
        check()
        probe.answer(tower.id, okResult(listOf(unraidNotification("n1"), unraidNotification("n2"))))
        probe.answer(backup.id, ProbeResult.Unreachable)

        val checks = check()

        assertEquals("only Tower raised something", listOf(tower.id), posted().map { it.tag }.distinct())
        assertEquals(listOf(tower.id, backup.id), checks.map { it.server.id })
        assertTrue("the pin of a server is handed to the probe", (tower.id to "AA:BB") in probe.probed)
        assertEquals("Backup's memory is its own", listOf("n1"), runBlocking { states.get(backup.id) }.seenNotificationIds)
    }

    @Test fun a_server_whose_check_throws_does_not_stop_the_others() {
        runBlocking { settings.setServers(listOf(backup, tower)) }
        val scripted = ScriptedProbe { server ->
            if (server.id == backup.id) error("boom") else okResult(listOf(unraidNotification("n1")))
        }
        check(scripted)
        val second = ScriptedProbe { server ->
            if (server.id == backup.id) error("boom") else okResult(listOf(unraidNotification("n1"), unraidNotification("n2")))
        }

        check(second)

        assertEquals(listOf(backup.id, tower.id), second.probed)
        assertEquals(listOf(tower.id), posted().map { it.tag }.distinct())
    }

    @Test fun a_server_that_hangs_is_cut_off_and_the_next_one_is_still_checked() {
        runBlocking { settings.setServers(listOf(backup, tower)) }
        val hanging = ScriptedProbe { server ->
            if (server.id == backup.id) { delay(60_000); ProbeResult.Unreachable } else okResult(listOf(unraidNotification("n1")))
        }
        check(hanging, timeoutMs = 50)
        val checked = ScriptedProbe { server ->
            if (server.id == backup.id) { delay(60_000); ProbeResult.Unreachable } else okResult(listOf(unraidNotification("n1"), unraidNotification("n2")))
        }

        val checks = check(checked, timeoutMs = 50)

        assertEquals(ProbeResult.Unreachable, checks.first { it.server.id == backup.id }.result)
        assertEquals(listOf(tower.id), posted().map { it.tag }.distinct())
    }

    @Test fun a_server_deleted_during_its_check_leaves_no_state_behind() {
        val deleting = ScriptedProbe { server ->
            settings.setServers(emptyList())
            okResult(listOf(unraidNotification("n1")))
        }

        check(deleting)

        assertEquals("nobody would ever remove that state", emptySet<String>(), states.serverIds)
    }

    @Test fun blocked_notifications_mean_no_network_request_at_all() {
        shadowOf(nm).setNotificationsEnabled(false)

        val checks = check()

        assertEquals("no request without a visible purpose", emptyList<String>(), probe.probed.map { it.first })
        assertEquals("the job ends itself", 1, workCancelled)
        assertEquals(emptyList<ServerCheck>(), checks)
    }

    @Test fun a_check_that_finds_alerts_switched_off_makes_no_request_and_cancels_the_job() {
        // WHY: a job that survived a failed cancel must not keep talking to the servers.
        runBlocking { settings.setAlertsEnabled(false) }

        check()

        assertEquals(emptyList<String>(), probe.probed.map { it.first })
        assertEquals(1, workCancelled)
    }

    @Test fun the_demo_server_is_never_asked_and_a_real_server_next_to_it_still_is() {
        // WHY: the demo entry has no address and no key; a check that touched it would count a
        // phantom server and spend a request on nothing. The probe fails the test if it is asked.
        runBlocking { settings.setServers(listOf(DemoFixtures.server, tower)) }
        val failingOnDemo = ScriptedProbe { server ->
            if (server.isDemo) throw AssertionError("the demo server must never be probed")
            okResult(listOf(unraidNotification("n1")))
        }

        val checks = check(failingOnDemo)

        assertEquals("only the real server is counted", listOf(tower.id), checks.map { it.server.id })
    }

    @Test fun a_list_with_only_the_demo_server_makes_no_request() {
        runBlocking { settings.setServers(listOf(DemoFixtures.server)) }

        val checks = check()

        assertEquals(emptyList<String>(), probe.probed.map { it.first })
        assertEquals(emptyList<ServerCheck>(), checks)
    }
}
