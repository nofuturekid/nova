package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.local.SettingsStore
import io.github.nofuturekid.nova.data.model.Server
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** At most this long per server; the job ends well inside the ten minutes Android allows. */
const val SERVER_CHECK_TIMEOUT_MS = 45_000L

/** How one server's check went. [result] is for the settings screen's last-check line. */
data class ServerCheck(val server: ServerRef, val result: ProbeResult, val posted: Int, val folded: Int)

/**
 * One run of the background check, everything the worker does apart from being a worker: for every
 * saved server, fetch once through [ServerProbe], decide with [evaluateAlerts], post what is new
 * and remember it. A server's run never affects another's: each has its own state, and one that
 * fails or hangs costs only itself.
 *
 * Network is never used for nothing: when alerts are off or Android blocks notifications the run
 * stops before the first request and cancels the job, because nothing could come of a check.
 *
 * Alerts are posted before the state is stored. If the process dies in between, the alert comes
 * a second time; the other order would lose it silently.
 */
@Singleton
class AlertCheckRunner internal constructor(
    private val settings: SettingsStore,
    private val probe: ServerProbe,
    private val states: AlertStateStore,
    private val notifier: AlertNotifier,
    private val cancelWork: suspend () -> Unit,
    private val serverTimeoutMs: Long,
) {
    @Inject
    constructor(
        settings: SettingsStore,
        probe: ServerProbe,
        states: AlertStateStore,
        notifier: AlertNotifier,
        scheduler: AlertScheduler,
    ) : this(settings, probe, states, notifier, scheduler::cancelWork, SERVER_CHECK_TIMEOUT_MS)

    suspend fun run(): List<ServerCheck> {
        val alerts = settings.alertSettings.first()
        if (!alerts.enabled || !notifier.canPost()) {
            cancelWork()
            return emptyList()
        }
        val snapshot = settings.serverSnapshot.first()
        return snapshot.servers
            .filter { it.isBackgroundCheckable() }
            .map { checkServer(it, snapshot.pins[it.id], alerts.level) }
    }

    private suspend fun checkServer(server: Server, pin: String?, level: AlertLevel): ServerCheck {
        val ref = ServerRef(server.id, server.name)
        val result = try {
            withTimeoutOrNull(serverTimeoutMs) { probe.probe(server, pin) } ?: ProbeResult.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ProbeResult.Unreachable
        }
        val previous = states.get(server.id)
        val evaluation = evaluateAlerts(ref, previous, result.loadedNotifications, result.loadedArray, level)
        val posted = notifier.post(ref, evaluation)
        // Not stored when Android refused the post, so the next check tries again. Not stored either
        // when the server was deleted while the check ran, which would leave state nobody removes.
        if (posted && evaluation.nextState != previous && stillSaved(server.id)) {
            states.put(server.id, evaluation.nextState)
        }
        return ServerCheck(ref, result, posted = evaluation.toPost.size, folded = evaluation.foldedCount)
    }

    private suspend fun stillSaved(serverId: String): Boolean =
        settings.servers.first().any { it.id == serverId }
}
