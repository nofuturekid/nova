package io.github.nofuturekid.nova.ui.screens.settings

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.nofuturekid.nova.data.alerts.ALERTS_CHANNEL_ID
import io.github.nofuturekid.nova.data.alerts.ALERT_WORK_NAME
import io.github.nofuturekid.nova.data.alerts.AlertLevel
import io.github.nofuturekid.nova.data.alerts.AlertNotifier
import io.github.nofuturekid.nova.data.alerts.AlertScheduler
import io.github.nofuturekid.nova.data.alerts.AlertSettings
import io.github.nofuturekid.nova.data.alerts.CheckOutcome
import io.github.nofuturekid.nova.data.alerts.CheckRecord
import io.github.nofuturekid.nova.data.alerts.FakeServerProbe
import io.github.nofuturekid.nova.data.alerts.InMemoryAlertStateStore
import io.github.nofuturekid.nova.data.alerts.ProbeResult
import io.github.nofuturekid.nova.data.alerts.ServerAlertState
import io.github.nofuturekid.nova.data.alerts.ServerProbe
import io.github.nofuturekid.nova.data.alerts.StallWatch
import io.github.nofuturekid.nova.data.alerts.okResult
import io.github.nofuturekid.nova.data.alerts.testServer
import io.github.nofuturekid.nova.data.alerts.unraidNotification
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.local.SettingsStore
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.repository.SettingsRepository
import io.github.nofuturekid.nova.ui.theme.ThemeMode
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * The real Settings, Notifications section on Robolectric, with the real settings store, the real
 * notifier and the real WorkManager test driver.
 *
 * WHY: this section is the only door to background work. These tests would fail if the switch
 * scheduled anything before Android granted the permission, if a denial left the switch on or said
 * nothing, if switching off left a job or kept talking to WorkManager, if a changed choice did not
 * reach the job, if a server without an https address looked checked, or if merely opening the
 * screen with alerts off initialised WorkManager.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w432dp-h2400dp-night-400dpi")
class AlertsSectionTest {
    @get:Rule val stallWatch = StallWatch()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var ctx: Context
    private lateinit var settings: SettingsStore
    private lateinit var notifier: AlertNotifier
    private lateinit var nm: NotificationManager
    private val states = InMemoryAlertStateStore()
    private val probe = FakeServerProbe()
    private var workManagerAsked = 0

    private val tower = testServer("srv-tower", "Tower")
    private val plain = Server("srv-plain", "Plain", "plain.example", "http://plain.example", "")

    @Before fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerImpl.setDelegate(null)
        settings = SettingsStore(ctx)
        settings.setAlertSettings(AlertSettings())
        settings.setServers(emptyList())
        settings.clearAllAlertStates()
        notifier = AlertNotifier(ctx)
        nm = ctx.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(true)
        nm.cancelAll()
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Settings and the work database outlive a test inside one JVM; the next class must find alerts off. */
    @After fun tearDown() = runBlocking {
        settings.setAlertSettings(AlertSettings())
        settings.setServers(emptyList())
        settings.clearAllAlertStates()
        WorkManagerImpl.setDelegate(null)
    }

    /** A scheduler on the real WorkManager test driver that counts how often WorkManager is asked for. */
    private fun scheduler(): AlertScheduler {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        return AlertScheduler(settings, states, notifier) {
            workManagerAsked++
            WorkManager.getInstance(ctx)
        }
    }

    private fun viewModel(scheduler: AlertScheduler) =
        AlertsViewModel(SettingsRepository(settings), scheduler, notifier, probe)

    private fun show(vm: AlertsViewModel) = rule.setContent {
        UnraidTheme(themeMode = ThemeMode.Dark) {
            Column(Modifier.verticalScroll(rememberScrollState())) { AlertsSection(vm) }
        }
    }

    /**
     * Conditions must not block on the settings store: the test thread is the main thread, and a
     * store write started by the view model runs its transform on the main thread, so a blocking
     * read here deadlocks it. Wait for what the screen shows instead, and read the store only once
     * the screen has settled.
     */
    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(timeoutMillis = 10_000) {
        // The view model's coroutines run on the main thread, which is this one: let them run between checks.
        rule.waitForIdle()
        condition()
    }

    private fun waitForText(text: String, substring: Boolean = false) = waitUntil {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun enabled() = runBlocking { settings.alertSettings.first().enabled }

    private fun works(): List<WorkInfo> =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ALERT_WORK_NAME).get().filter { !it.state.isFinished }

    /** Alerts on, one server, one scheduled job. Returns the scheduler: a second one would reset the WorkManager driver. */
    private fun switchOnWithOneServer(): AlertScheduler {
        val scheduler = scheduler()
        runBlocking {
            settings.setServers(listOf(tower))
            assertTrue(scheduler.enable())
        }
        return scheduler
    }

    private fun answerPermissionRequest(granted: Boolean) {
        val request = shadowOf(rule.activity).lastRequestedPermission
        rule.runOnUiThread {
            @Suppress("DEPRECATION")
            rule.activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
            )
        }
    }

    // WHY: the maintainer's rule is that with the switch off NOVA does nothing in the background, and
    // WorkManager is initialised on demand for exactly that reason. Everything the screen reads and every
    // action that is a no-op while off must leave WorkManager uninitialised, on the production scheduler.
    @Test fun opening_the_section_with_alerts_off_does_not_initialise_workmanager() {
        runBlocking { settings.setServers(listOf(tower)) }
        val vm = AlertsViewModel(SettingsRepository(settings), AlertScheduler(ctx, settings, states, notifier), notifier, probe)

        show(vm)
        waitForText("Off. NOVA does nothing in the background.")
        vm.turnOff()
        vm.setInterval(60)
        vm.setLevel(AlertLevel.AlertsOnly)
        vm.setUnmeteredOnly(true)
        vm.refreshNotificationsAllowed()
        rule.waitForIdle()

        assertFalse("WorkManager must not be initialised", WorkManager.isInitialized())
        rule.onNodeWithText("Check every").assertDoesNotExist()
        assertFalse(enabled())
    }

    // WHY: on Android 13 and later notifications are off until the user allows them; a job scheduled
    // before that would run and could never be shown.
    @Test fun switching_on_without_the_permission_requests_it_and_schedules_nothing_until_granted() {
        runBlocking { settings.setServers(listOf(tower)) }
        val vm = viewModel(scheduler())
        show(vm)
        waitForText("Off. NOVA does nothing in the background.")

        rule.onAllNodes(isToggleable())[0].performClick()
        waitForText("Get alerts outside the app?")
        rule.onNodeWithText("Turn on").performClick()
        rule.waitForIdle()

        val request = shadowOf(rule.activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
        assertFalse("the switch stays off until the answer", enabled())
        assertEquals("nothing is scheduled before the permission is granted", 0, workManagerAsked)

        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        answerPermissionRequest(granted = true)
        waitForText("On. NOVA checks your servers about every 30 minutes.")
        waitUntil { works().size == 1 }
        assertTrue(enabled())
    }

    // WHY: a user who says no must see why nothing happened and how to fix it, and the switch must not
    // claim alerts are on.
    @Test fun denying_the_permission_leaves_the_switch_off_and_explains() {
        runBlocking { settings.setServers(listOf(tower)) }
        shadowOf(nm).setNotificationsEnabled(false)
        val vm = viewModel(scheduler())
        show(vm)
        waitForText("Off. NOVA does nothing in the background.")

        rule.onAllNodes(isToggleable())[0].performClick()
        waitForText("Turn on")
        rule.onNodeWithText("Turn on").performClick()
        rule.waitForIdle()
        answerPermissionRequest(granted = false)

        waitForText("Android blocks notifications for NOVA", substring = true)
        rule.onNodeWithText("Open notification settings").assertExists()
        rule.onNodeWithText("Off. NOVA does nothing in the background.").assertExists()
        assertFalse(enabled())
        assertEquals(0, workManagerAsked)
        assertTrue(works().isEmpty())
    }

    @Test fun not_now_in_the_explanation_changes_nothing() {
        runBlocking { settings.setServers(listOf(tower)) }
        val vm = viewModel(scheduler())
        show(vm)
        waitForText("Off. NOVA does nothing in the background.")

        rule.onAllNodes(isToggleable())[0].performClick()
        waitForText("Get alerts outside the app?")
        rule.onNodeWithText("Not now").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Get alerts outside the app?").assertDoesNotExist()
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
        assertFalse(enabled())
        assertEquals(0, workManagerAsked)
    }

    // WHY: "switch off" has to mean off: no job, nothing left posted, and a screen that does not poke
    // WorkManager again (that would create its database and alarm after the user said no background work).
    @Test fun switching_off_cancels_the_work_and_the_screen_makes_no_workmanager_call_afterwards() {
        val vm = viewModel(switchOnWithOneServer())
        show(vm)
        waitForText("Check every")
        assertEquals(1, works().size)

        rule.onAllNodes(isToggleable())[0].performClick()
        waitForText("Off. NOVA does nothing in the background.")
        waitUntil { works().isEmpty() }
        val askedWhenOff = workManagerAsked

        vm.turnOff()
        vm.setInterval(60)
        vm.setUnmeteredOnly(true)
        vm.refreshNotificationsAllowed()
        rule.waitForIdle()

        assertEquals("no WorkManager call once alerts are off", askedWhenOff, workManagerAsked)
        rule.onNodeWithText("Check every").assertDoesNotExist()
        assertTrue(works().isEmpty())
    }

    // WHY: a choice that does not reach the job is a lie on screen: "every hour" while Android still
    // runs the old period, or "Wi-Fi only" while the job also runs on mobile data.
    @Test fun changing_the_interval_or_the_network_reschedules_the_existing_work() {
        val vm = viewModel(switchOnWithOneServer())
        show(vm)
        waitForText("Check every")

        rule.onNodeWithText("1 hour").performClick()
        waitUntil { works().singleOrNull()?.periodicityInfo?.repeatIntervalMillis == TimeUnit.MINUTES.toMillis(60) }
        waitForText("On. NOVA checks your servers about every hour.")

        rule.onAllNodes(isToggleable())[1].performClick()
        waitUntil { works().singleOrNull()?.constraints?.requiredNetworkType == NetworkType.UNMETERED }

        assertEquals("still one unique job", 1, works().size)
        assertEquals(60, runBlocking { settings.alertSettings.first() }.intervalMinutes)
        assertTrue(runBlocking { settings.alertSettings.first() }.unmeteredOnly)
    }

    // WHY: a server with only an http address is skipped in the background. The user must be told, and told
    // what fixes it, instead of reading a silent "OK".
    @Test fun a_server_that_is_not_encrypted_says_so_and_what_it_needs() {
        runBlocking {
            settings.setServers(listOf(plain, tower))
            settings.setAlertSettings(AlertSettings(enabled = true))
            // Even a stale "OK" record must not make an http-only server look checked.
            settings.setAlertState(plain.id, ServerAlertState(lastCheck = CheckRecord(1_000, CheckOutcome.Ok)))
        }
        show(viewModel(scheduler()))

        waitForText("Not checked in the background because the connection is not encrypted.", substring = true)
        rule.onNodeWithText("An https address is needed.", substring = true).assertExists()
        rule.onNodeWithText("Plain").assertExists()
    }

    @Test fun every_failure_outcome_reads_differently_on_the_last_check_line() {
        runBlocking {
            settings.setServers(listOf(tower))
            settings.setAlertSettings(AlertSettings(enabled = true))
        }
        val vm = viewModel(scheduler())
        show(vm)
        val expected = mapOf(
            CheckOutcome.Unreachable to "Could not be reached",
            CheckOutcome.CertificateProblem to "Certificate problem",
            CheckOutcome.KeyMissing to "API key missing",
            CheckOutcome.KeyUnreadable to "API key cannot be read",
            CheckOutcome.Rejected to "API key refused",
            CheckOutcome.Ok to "OK",
        )
        expected.forEach { (outcome, text) ->
            runBlocking { settings.setAlertState(tower.id, ServerAlertState(lastCheck = CheckRecord(1_000, outcome))) }
            waitForText(text, substring = true)
        }
    }

    // WHY: the demo server has fictional data and no address; it is never checked, and the screen must
    // not suggest otherwise, with alerts off or on.
    @Test fun the_demo_server_is_named_as_never_checked_and_cannot_be_tested() {
        runBlocking { settings.setServers(listOf(DemoFixtures.server)) }
        val vm = viewModel(scheduler())
        show(vm)

        waitForText("The demo server is never checked.", substring = true)
        assertEquals(0, workManagerAsked)

        runBlocking { settings.setAlertSettings(AlertSettings(enabled = true)) }
        waitForText("Send test notification")
        rule.onNodeWithText("Send test notification").assertIsNotEnabled()
        rule.onNodeWithText("Demo server, never checked", substring = true).assertExists()
        assertEquals("the demo server is not a server to schedule for", 0, probe.probed.size)
    }

    // WHY: the test action proves key, network, channel and tap target on a real server, so it must show
    // a real notification; and it must never write the remembered state, or a test could swallow a real
    // alert (marked as seen) or repeat one.
    @Test fun the_test_action_posts_the_newest_alert_and_does_not_touch_the_remembered_state() {
        val remembered = ServerAlertState(
            notificationsBaselined = true, seenNotificationIds = listOf("n1"), conditionsBaselined = true,
            lastCheck = CheckRecord(1_000, CheckOutcome.Ok),
        )
        runBlocking {
            settings.setServers(listOf(tower))
            settings.setAlertSettings(AlertSettings(enabled = true))
            settings.setAlertState(tower.id, remembered)
        }
        probe.answer(
            tower.id,
            okResult(
                listOf(
                    unraidNotification("n1", subject = "Old alert", timestamp = "2026-11-13T08:00:00.000Z"),
                    unraidNotification("n2", subject = "Disk 1 is hot", timestamp = "2026-11-13T10:00:00.000Z"),
                ),
            ),
        )
        show(viewModel(scheduler()))
        waitForText("Send test notification")

        rule.onNodeWithText("Send test notification").performClick()

        waitForText("Test: Disk 1 is hot")
        val posted = nm.activeNotifications.single()
        assertEquals(tower.id, posted.tag)
        assertEquals(remembered, runBlocking { settings.alertStateFor(tower.id) })
    }

    // WHY: with the app-level permission granted, a user who switches off one of NOVA's channels would
    // otherwise read "On" while every alert of that channel is silently dropped (and marked as seen).
    @Test fun a_blocked_channel_is_named_with_a_way_to_fix_it() {
        runBlocking {
            settings.setServers(listOf(tower))
            settings.setAlertSettings(AlertSettings(enabled = true))
        }
        notifier.ensureChannels()
        show(viewModel(scheduler()))
        waitForText("Send test notification")
        rule.onNodeWithText("Open notification settings").assertDoesNotExist()

        nm.deleteNotificationChannel(ALERTS_CHANNEL_ID)
        nm.createNotificationChannel(NotificationChannel(ALERTS_CHANNEL_ID, "Server alerts", NotificationManager.IMPORTANCE_NONE))
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

        waitForText("Android blocks NOVA's \"Server alerts\" or \"Server warnings\" notifications", substring = true)
        rule.onNodeWithText("Open notification settings").assertExists()
    }

    // WHY: "switching off cancels the work": a test already waiting on a slow server must not post a
    // notification after the user said off.
    @Test fun switching_off_while_a_test_is_running_posts_nothing() {
        val gate = CompletableDeferred<ProbeResult>()
        var entered = false
        val slow = object : ServerProbe {
            override suspend fun probe(server: Server, pin: String?): ProbeResult {
                entered = true
                return gate.await()
            }
        }
        val scheduler = switchOnWithOneServer()
        val vm = AlertsViewModel(SettingsRepository(settings), scheduler, notifier, slow)
        show(vm)
        waitForText("Send test notification")

        rule.onNodeWithText("Send test notification").performClick()
        waitUntil { entered }
        vm.turnOff()
        waitForText("Off. NOVA does nothing in the background.")
        gate.complete(okResult(listOf(unraidNotification("n2", subject = "Disk 1 is hot", timestamp = "2026-11-13T10:00:00.000Z"))))
        rule.waitForIdle()

        assertTrue("no notification after the switch is off", nm.activeNotifications.isEmpty())
        assertFalse(enabled())
    }

    @Test fun the_test_action_on_a_server_that_cannot_be_reached_says_so_and_posts_nothing() {
        runBlocking {
            settings.setServers(listOf(tower))
            settings.setAlertSettings(AlertSettings(enabled = true))
        }
        probe.answer(tower.id, ProbeResult.Unreachable)
        show(viewModel(scheduler()))
        waitForText("Send test notification")

        rule.onNodeWithText("Send test notification").performClick()

        waitForText("Test: Tower: could not be reached.")
        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test fun the_test_action_with_no_unread_alerts_says_the_server_was_reached() {
        runBlocking {
            settings.setServers(listOf(tower))
            settings.setAlertSettings(AlertSettings(enabled = true))
        }
        probe.answer(tower.id, okResult(emptyList()))
        show(viewModel(scheduler()))
        waitForText("Send test notification")

        rule.onNodeWithText("Send test notification").performClick()

        waitForText("Test: NOVA reached Tower. No unread alerts or warnings.")
        assertTrue(nm.activeNotifications.isEmpty())
    }
}
