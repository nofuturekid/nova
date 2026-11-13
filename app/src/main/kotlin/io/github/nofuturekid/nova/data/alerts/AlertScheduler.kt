package io.github.nofuturekid.nova.data.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nofuturekid.nova.data.local.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The one unique periodic work that checks the servers. */
const val ALERT_WORK_NAME = "nova-alert-check"

/** The shortest flex window WorkManager accepts for periodic work. */
private const val MIN_FLEX_MIN = 5L

/**
 * Starts and stops the background check (ADR-0048). The settings screen calls [enable],
 * [update] and [disable]; the app calls [reconcile] at start and [onServerRemoved] when a server
 * entry is deleted.
 *
 * The rule everything here follows: while alerts are off, WorkManager is not touched. It is
 * initialised on demand (see `NovaApp`), so a call that would only learn "there is nothing to do"
 * returns before it asks for the WorkManager instance. That is why [disable], [reconcile] and
 * [onServerRemoved] read the settings first and do nothing when alerts are off, and why nothing
 * here runs at process start except that one read.
 *
 * Servers that cannot be checked in the background ([isBackgroundCheckable]) do not count: a
 * list of only such servers has no work to schedule.
 */
@Singleton
class AlertScheduler internal constructor(
    private val settings: SettingsStore,
    private val states: AlertStateStore,
    private val notifier: AlertNotifier,
    /** Asked only when work is enqueued or cancelled, never while alerts are off. */
    private val workManager: () -> WorkManager,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: SettingsStore,
        states: AlertStateStore,
        notifier: AlertNotifier,
    ) : this(settings, states, notifier, { WorkManager.getInstance(context) })

    /**
     * Switches alerts on with the given choices: saves them, creates the notification channels and
     * schedules the check. Returns false and changes nothing when Android blocks NOVA's
     * notifications (on Android 13 and later: the permission is missing); the caller asks for the
     * permission first and shows the blocked state when it stays denied.
     *
     * With no server to check the choices are saved and nothing is scheduled; [reconcile]
     * schedules the job once a server is saved.
     */
    suspend fun enable(
        intervalMinutes: Int = DEFAULT_ALERT_INTERVAL_MIN,
        level: AlertLevel = DEFAULT_ALERT_LEVEL,
        unmeteredOnly: Boolean = false,
    ): Boolean {
        if (!notifier.canPost()) return false
        notifier.ensureChannels()
        val chosen = AlertSettings(true, normalizeAlertInterval(intervalMinutes), level, unmeteredOnly)
        settings.setAlertSettings(chosen)
        schedule(chosen)
        return true
    }

    /**
     * Applies new choices while alerts are on (interval, level, network): saves them and updates
     * the existing work, which keeps its place in the period. Does nothing while alerts are off.
     */
    suspend fun update(intervalMinutes: Int, level: AlertLevel, unmeteredOnly: Boolean) {
        if (!settings.alertSettings.first().enabled) return
        val chosen = AlertSettings(true, normalizeAlertInterval(intervalMinutes), level, unmeteredOnly)
        settings.setAlertSettings(chosen)
        schedule(chosen)
    }

    /**
     * Switches alerts off and leaves nothing behind: the unique work is cancelled, everything NOVA
     * posted is removed and the remembered state is cleared, so switching on again starts with a
     * silent first check. The notification channels stay, because deleting them would show
     * "categories deleted" in Android's settings. Does nothing, and does not touch WorkManager, when
     * alerts were already off.
     *
     * The work is cancelled before the switch is saved: a crash in between leaves a switch that
     * is still on, which [reconcile] repairs, not an orphaned job that nobody can see.
     */
    suspend fun disable() {
        val current = settings.alertSettings.first()
        if (!current.enabled) return
        cancelWork()
        notifier.cancelAll()
        states.clear()
        settings.setAlertsEnabled(false)
    }

    /**
     * Brings the scheduled work in line with the settings. Called at app start: it repairs a job
     * that a force stop or a battery manager cancelled, schedules once a first server exists and
     * cancels when none is left or when notifications are blocked. Does nothing, and does not touch
     * WorkManager, while alerts are off.
     */
    suspend fun reconcile() {
        val current = settings.alertSettings.first()
        if (!current.enabled) return
        schedule(current)
    }

    /**
     * A server entry was deleted (its remembered state is removed with the entry): removes what
     * NOVA posted for it and, when no server is left to check, cancels the job. The switch keeps
     * its value; saving a server again schedules the job through [reconcile].
     */
    suspend fun onServerRemoved(serverId: String) {
        quietly {
            notifier.cancelForServer(serverId)
            reconcile()
        }
    }

    /**
     * [reconcile] for callers that must not fail because of alerts: app start and saving a server.
     * A scheduling problem is not worth a crash or a lost server entry; the next start tries again.
     */
    suspend fun reconcileQuietly() = quietly { reconcile() }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // See above.
        }
    }

    /** Cancels the unique work, used by the check itself when it finds alerts switched off. */
    internal suspend fun cancelWork() {
        withContext(Dispatchers.IO) { workManager().cancelUniqueWork(ALERT_WORK_NAME).await() }
    }

    private suspend fun schedule(current: AlertSettings) {
        if (!notifier.canPost() || !hasCheckableServer()) {
            cancelWork()
            return
        }
        withContext(Dispatchers.IO) {
            workManager().enqueueUniquePeriodicWork(
                ALERT_WORK_NAME,
                // UPDATE applies a changed interval or network option without restarting the period.
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest(current),
            ).await()
        }
    }

    private suspend fun hasCheckableServer(): Boolean =
        settings.servers.first().any { it.isBackgroundCheckable() }
}

/**
 * Interval from the settings, flex window of half of it (at least 5 minutes, so Android can batch
 * the job with others), constraint "connected" or, with Wi-Fi only, "unmetered". No battery or
 * charging constraint: a failing disk is worth knowing about at low battery too. No backoff: the
 * worker never asks for a retry.
 */
internal fun periodicRequest(settings: AlertSettings) =
    PeriodicWorkRequestBuilder<AlertCheckWorker>(
        repeatInterval = settings.intervalMinutes.toLong(),
        repeatIntervalTimeUnit = TimeUnit.MINUTES,
        flexTimeInterval = maxOf(MIN_FLEX_MIN, settings.intervalMinutes / 2L),
        flexTimeIntervalUnit = TimeUnit.MINUTES,
    ).setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build(),
    ).build()
