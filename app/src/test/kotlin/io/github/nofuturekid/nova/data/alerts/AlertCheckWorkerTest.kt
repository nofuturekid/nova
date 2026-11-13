package io.github.nofuturekid.nova.data.alerts

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.nofuturekid.nova.data.local.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * WHY: a failed check must be invisible. A worker that returned retry or failure would make
 * Android schedule extra wake-ups with growing delays for a problem the next period retries
 * anyway, and a worker that let an exception out would count as a crash. The last test builds
 * the worker the way WorkManager does, through the Hilt graph, so a broken binding or entry point
 * shows up here and not on the phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AlertCheckWorkerTest {
    private lateinit var ctx: Context
    private lateinit var settings: SettingsStore
    private lateinit var nm: NotificationManager
    private val states = InMemoryAlertStateStore()
    private val tower = testServer("srv-tower", "Tower")

    @Before fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        WorkManagerImpl.setDelegate(null)
        settings = SettingsStore(ctx)
        settings.setAlertSettings(AlertSettings(enabled = true))
        settings.setServers(listOf(tower))
        nm = ctx.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(true)
        nm.cancelAll()
    }

    @After fun tearDown() = runBlocking {
        settings.setAlertSettings(AlertSettings())
        settings.setServers(emptyList())
        WorkManagerImpl.setDelegate(null)
    }

    private fun worker(runner: AlertCheckRunner): AlertCheckWorker =
        TestListenableWorkerBuilder<AlertCheckWorker>(ctx)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    AlertCheckWorker(appContext, workerParameters) { runner }
            })
            .build()

    private fun runner(probe: ServerProbe, store: AlertStateStore = states) =
        AlertCheckRunner(settings, probe, store, AlertNotifier(ctx), {}, SERVER_CHECK_TIMEOUT_MS)

    @Test fun an_unreachable_server_ends_in_success_and_posts_nothing() = runBlocking {
        val result = worker(runner(FakeServerProbe())).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun a_check_that_throws_still_ends_in_success() = runBlocking {
        val brokenStore = object : AlertStateStore by states {
            override suspend fun get(serverId: String): ServerAlertState = error("disk full")
        }
        val probe = FakeServerProbe().apply { answer(tower.id, okResult(emptyList())) }

        val result = worker(runner(probe, brokenStore)).doWork()

        assertEquals("no retry, the next period is the retry", ListenableWorker.Result.success(), result)
    }

    @Test fun a_new_alert_found_by_the_worker_is_posted() = runBlocking {
        val probe = FakeServerProbe()
        val runner = runner(probe)
        probe.answer(tower.id, okResult(listOf(unraidNotification("n1"))))
        worker(runner).doWork()
        probe.answer(tower.id, okResult(listOf(unraidNotification("n1"), unraidNotification("n2"))))

        val result = worker(runner).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(notificationId(tower.id, "n2")), nm.activeNotifications.map { it.id })
    }

    @Test fun the_worker_WorkManager_builds_by_class_name_finds_its_runner_in_the_hilt_graph() = runBlocking {
        // Alerts are off for this one: the real runner stops before any request and cancels the
        // job, which exercises the real scheduler on the test WorkManager.
        settings.setAlertSettings(AlertSettings(enabled = false))
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)

        val result = TestListenableWorkerBuilder<AlertCheckWorker>(ctx).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, nm.activeNotifications.size)
    }
}
