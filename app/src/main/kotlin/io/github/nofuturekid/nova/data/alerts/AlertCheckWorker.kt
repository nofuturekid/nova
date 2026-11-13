package io.github.nofuturekid.nova.data.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/** What the worker needs from the Hilt graph; WorkManager builds the worker itself, so it asks for it here. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlertsEntryPoint {
    fun alertCheckRunner(): AlertCheckRunner
    fun pendingNavigation(): PendingNavigation
}

/**
 * The periodic job (`nova-alert-check`). It always ends with success, never with a retry: a retry
 * adds wake-ups with growing delays, and the next period is the retry. A failed check is not an
 * alert and not an error worth reporting; its outcome is for the settings screen, not for here.
 */
class AlertCheckWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val runner: () -> AlertCheckRunner,
) : CoroutineWorker(context, params) {

    /** The constructor WorkManager's default factory calls. */
    constructor(context: Context, params: WorkerParameters) : this(
        context, params,
        { EntryPointAccessors.fromApplication(context.applicationContext, AlertsEntryPoint::class.java).alertCheckRunner() },
    )

    override suspend fun doWork(): Result {
        try {
            runner().run()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Quiet by design, see above.
        }
        return Result.success()
    }
}
