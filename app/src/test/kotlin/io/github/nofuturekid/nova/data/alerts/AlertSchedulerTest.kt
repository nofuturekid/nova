package io.github.nofuturekid.nova.data.alerts

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.nofuturekid.nova.data.local.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * WHY: the maintainer's rule is that background work exists only while alerts are switched on.
 * These tests pin both halves of it on the real WorkManager test driver: with the switch off the
 * scheduler never even asks for WorkManager (which is initialised on demand, so asking would
 * create its database and alarm), and with it on there is exactly one unique periodic job, with
 * the chosen interval and network constraint, which every way of switching off or running out of
 * servers removes again.
 *
 * Settings and the work database outlive a test inside one JVM; every test starts from "off, no
 * server, no work" and leaves it that way.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AlertSchedulerTest {
    private lateinit var ctx: Context
    private lateinit var settings: SettingsStore
    private lateinit var notifier: AlertNotifier
    private val states = InMemoryAlertStateStore()
    private var workManagerAsked = 0

    private val tower = testServer("srv-tower", "Tower")
    private val backup = testServer("srv-backup", "Backup")

    @Before fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerImpl.setDelegate(null)
        settings = SettingsStore(ctx)
        settings.setAlertSettings(AlertSettings())
        settings.setServers(emptyList())
        notifier = AlertNotifier(ctx)
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
    }

    @After fun tearDown() = runBlocking {
        settings.setAlertSettings(AlertSettings())
        settings.setServers(emptyList())
        WorkManagerImpl.setDelegate(null)
    }

    /** A scheduler that fails the test the moment it asks for WorkManager. */
    private fun schedulerThatMustNotTouchWorkManager() = AlertScheduler(settings, states, notifier) {
        workManagerAsked++
        error("WorkManager was asked for while alerts are off")
    }

    /** A scheduler on the real WorkManager test driver. */
    private fun scheduler(): AlertScheduler {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        return AlertScheduler(settings, states, notifier) { WorkManager.getInstance(ctx) }
    }

    private fun works(): List<WorkInfo> =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ALERT_WORK_NAME).get()

    private fun active(): List<WorkInfo> = works().filter { !it.state.isFinished }

    @Test fun with_alerts_off_nothing_is_scheduled_and_workmanager_is_never_asked_for() = runBlocking {
        settings.setServers(listOf(tower))
        val scheduler = schedulerThatMustNotTouchWorkManager()

        scheduler.reconcile()
        scheduler.reconcileQuietly()
        scheduler.update(60, AlertLevel.AlertsOnly, unmeteredOnly = true)
        scheduler.disable()
        scheduler.onServerRemoved(tower.id)

        assertEquals(0, workManagerAsked)
        assertFalse("WorkManager must not be initialised", WorkManager.isInitialized())
        assertFalse(settings.alertSettings.first().enabled)
    }

    @Test fun enabling_enqueues_exactly_one_unique_periodic_work_with_the_chosen_constraints() = runBlocking {
        settings.setServers(listOf(tower, backup))
        val scheduler = scheduler()

        assertTrue(scheduler.enable(intervalMinutes = 60, level = AlertLevel.AlertsOnly, unmeteredOnly = true))

        val work = active().single()
        assertEquals(TimeUnit.MINUTES.toMillis(60), work.periodicityInfo!!.repeatIntervalMillis)
        assertEquals("flex is half the interval", TimeUnit.MINUTES.toMillis(30), work.periodicityInfo!!.flexIntervalMillis)
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        assertFalse("no battery or charging constraint", work.constraints.requiresBatteryNotLow() || work.constraints.requiresCharging())
        assertEquals(AlertSettings(true, 60, AlertLevel.AlertsOnly, true), settings.alertSettings.first())
    }

    @Test fun the_defaults_are_thirty_minutes_on_any_connected_network_with_a_flex_of_fifteen() = runBlocking {
        settings.setServers(listOf(tower))
        val scheduler = scheduler()

        scheduler.enable()

        val work = active().single()
        assertEquals(TimeUnit.MINUTES.toMillis(30), work.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(15), work.periodicityInfo!!.flexIntervalMillis)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test fun the_shortest_interval_keeps_the_flex_window_Android_requires() = runBlocking {
        // WHY: 15 minutes / 2 is below WorkManager's 5 minute flex floor only for tiny intervals; the
        // floor must hold or the request is rejected and no alert is ever checked.
        settings.setServers(listOf(tower))
        scheduler().enable(intervalMinutes = 15)

        val info = active().single().periodicityInfo!!
        assertEquals(TimeUnit.MINUTES.toMillis(15), info.repeatIntervalMillis)
        assertTrue(info.flexIntervalMillis >= TimeUnit.MINUTES.toMillis(5))
    }

    @Test fun enabling_is_refused_while_android_blocks_notifications_and_changes_nothing() = runBlocking {
        settings.setServers(listOf(tower))
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val scheduler = schedulerThatMustNotTouchWorkManager()

        assertFalse(scheduler.enable())

        assertFalse("the switch stays off", settings.alertSettings.first().enabled)
        assertEquals(0, workManagerAsked)
    }

    @Test fun a_changed_interval_or_network_choice_updates_the_one_existing_work() = runBlocking {
        settings.setServers(listOf(tower))
        val scheduler = scheduler()
        scheduler.enable(intervalMinutes = 30)
        val id = active().single().id

        scheduler.update(180, AlertLevel.AlertsAndWarnings, unmeteredOnly = true)

        val work = active().single()
        assertEquals("the same work, not a second one", id, work.id)
        assertEquals(TimeUnit.MINUTES.toMillis(180), work.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
    }

    @Test fun disabling_cancels_the_work_removes_notifications_and_forgets_the_state() = runBlocking {
        settings.setServers(listOf(tower))
        val scheduler = scheduler()
        scheduler.enable()
        states.put(tower.id, ServerAlertState(true, listOf("n1"), true, setOf("array-stopped")))
        notifier.post(ServerRef(tower.id, tower.name), evaluationWithOneAlert())
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertEquals("setup: something was posted", 1, nm.activeNotifications.size)

        scheduler.disable()

        assertTrue("no unfinished work is left", active().isEmpty())
        assertEquals(0, nm.activeNotifications.size)
        assertEquals("a fresh first check next time", emptySet<String>(), states.serverIds)
        assertFalse(settings.alertSettings.first().enabled)
    }

    @Test fun switching_off_keeps_the_choices_for_the_next_time() = runBlocking {
        settings.setServers(listOf(tower))
        val scheduler = scheduler()
        scheduler.enable(intervalMinutes = 180, level = AlertLevel.AlertsOnly, unmeteredOnly = true)

        scheduler.disable()

        assertEquals(AlertSettings(false, 180, AlertLevel.AlertsOnly, true), settings.alertSettings.first())
    }

    @Test fun removing_the_last_server_cancels_the_work_but_removing_another_keeps_it() = runBlocking {
        settings.setServers(listOf(tower, backup))
        val scheduler = scheduler()
        scheduler.enable()

        settings.setServers(listOf(tower))
        scheduler.onServerRemoved(backup.id)
        assertEquals("one server is left to check", 1, active().size)

        settings.setServers(emptyList())
        scheduler.onServerRemoved(tower.id)
        assertTrue("no server, no job", active().isEmpty())
        assertTrue("the switch keeps its value", settings.alertSettings.first().enabled)
    }

    @Test fun a_server_saved_later_brings_the_job_back() = runBlocking {
        val scheduler = scheduler()
        assertTrue(scheduler.enable())
        assertTrue("no server yet, so nothing is scheduled", active().isEmpty())

        settings.setServers(listOf(tower))
        scheduler.reconcile()

        assertEquals(1, active().size)
    }

    @Test fun removing_a_server_takes_down_what_was_posted_for_it() = runBlocking {
        settings.setServers(listOf(tower, backup))
        val scheduler = scheduler()
        scheduler.enable()
        notifier.post(ServerRef(tower.id, tower.name), evaluationWithOneAlert())
        notifier.post(ServerRef(backup.id, backup.name), evaluationWithOneAlert())
        val nm = ctx.getSystemService(NotificationManager::class.java)

        settings.setServers(listOf(tower))
        scheduler.onServerRemoved(backup.id)

        assertEquals(listOf(tower.id), nm.activeNotifications.map { it.tag })
    }

    @Test fun reconcile_repairs_a_schedule_that_a_force_stop_or_battery_manager_cancelled() = runBlocking {
        settings.setServers(listOf(tower))
        val scheduler = scheduler()
        scheduler.enable()
        WorkManager.getInstance(ctx).cancelUniqueWork(ALERT_WORK_NAME).result.get()
        assertTrue("setup: the job is gone", active().isEmpty())

        scheduler.reconcile()

        assertEquals(1, active().size)
    }

    @Test fun reconcile_cancels_the_job_when_notifications_got_blocked() = runBlocking {
        // WHY: with notifications blocked a check could only waste battery and data.
        settings.setServers(listOf(tower))
        val scheduler = scheduler()
        scheduler.enable()
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)

        scheduler.reconcile()

        assertTrue(active().isEmpty())
        assertTrue("the switch is untouched; allowing notifications again resumes", settings.alertSettings.first().enabled)
    }

    @Test fun enabling_creates_both_channels_with_their_importance() = runBlocking {
        settings.setServers(listOf(tower))
        scheduler().enable()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(ALERTS_CHANNEL_ID).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(WARNINGS_CHANNEL_ID).importance)
    }

    @Test fun nothing_creates_channels_before_alerts_are_switched_on() = runBlocking {
        // WHY: a user who never switches alerts on must not get "Server alerts" in Android's
        // notification settings for an app that sends none.
        settings.setServers(listOf(tower))
        schedulerThatMustNotTouchWorkManager().reconcileQuietly()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertEquals(null, nm.getNotificationChannel(ALERTS_CHANNEL_ID))
        assertEquals(null, nm.getNotificationChannel(WARNINGS_CHANNEL_ID))
    }

    private fun evaluationWithOneAlert(): AlertEvaluation {
        val first = evaluateAlerts(ServerRef("x", "x"), ServerAlertState(), okResult(emptyList()).notifications, okResult(emptyList()).array, AlertLevel.AlertsAndWarnings)
        return evaluateAlerts(
            ServerRef("x", "x"), first.nextState,
            okResult(listOf(unraidNotification("n1"))).notifications, okResult(emptyList()).array, AlertLevel.AlertsAndWarnings,
        )
    }
}
