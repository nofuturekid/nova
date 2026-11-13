package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.model.CapabilityTarget
import io.github.nofuturekid.nova.data.model.Detection
import io.github.nofuturekid.nova.data.model.ServerCapabilities
import io.github.nofuturekid.nova.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The capabilities of the active server: its API version and what its API key may do (ADR-0047).
 * Built like [io.github.nofuturekid.nova.data.update.UpdateTracker]: a singleton that detects in
 * the application scope, so a detection is not lost when a screen goes away, and holds the result
 * in memory only (a server upgrade or a changed key would leave a stored result stale).
 *
 * Detection runs on exactly three occasions and no other: the TARGET changes (server id, resolved
 * address or API key; a renamed server or a changed accent is not a change), [refresh] is called
 * (pull-to-refresh, return to the foreground), or the app starts. There is no timer, no retry loop
 * and no polling, so nothing runs while the app is in the background.
 *
 * Fail open, by construction:
 *  - a new target starts at [Detection.Detecting] with unknown access, so one server's
 *    "denied" never gates another server;
 *  - a failed detection never replaces a result that was good for the same target, so a
 *    transient error cannot make controls appear and disappear;
 *  - a result for a target that is no longer current is dropped.
 */
@Singleton
class CapabilityRepository internal constructor(
    private val scope: CoroutineScope,
    active: Flow<ActiveServer?>,
    private val targetOf: (ActiveServer?) -> CapabilityTarget?,
    private val probe: suspend (ActiveServer) -> ProbeOutcome,
) {
    @Inject
    constructor(
        servers: ServerRepository,
        unraid: UnraidGateway,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        scope = scope,
        active = servers.activeWithKey,
        targetOf = unraid::capabilityTarget,
        probe = unraid::probeCapabilities,
    )

    private val state = MutableStateFlow(ServerCapabilities.UNKNOWN)

    /** The capabilities of the active server. Starts, and stays, unknown until a detection has answered. */
    val capabilities: StateFlow<ServerCapabilities> = state.asStateFlow()

    private class Running(val target: CapabilityTarget, val job: Job)

    /** Guards [currentActive], [running] and the start of a detection. */
    private val lock = Mutex()
    private var currentActive: ActiveServer? = null
    private var running: Running? = null

    // Last in the property list: the launched job reads every property above.
    init {
        scope.launch { active.collect(::onActive) }
    }

    /**
     * Detects again for the current target: pull-to-refresh, and the app returning to the foreground
     * (the user may have widened or narrowed the key in the Unraid web UI, which keeps the same key
     * string). Joins a detection that is already running for the same target; never runs two in
     * parallel. The last good result stays visible while it runs.
     */
    fun refresh() {
        scope.launch {
            lock.withLock {
                val a = currentActive
                val target = targetOf(a)
                if (a != null && target != null) startUnlessRunning(a, target)
            }
        }
    }

    private suspend fun onActive(a: ActiveServer?) = lock.withLock {
        currentActive = a // always: a pin or mode change must reach the next probe even if the target is equal
        val target = targetOf(a)
        if (target == state.value.target) return@withLock
        running?.job?.cancel()
        running = null
        if (target == null) {
            state.value = ServerCapabilities.UNKNOWN
        } else {
            state.value = ServerCapabilities(target = target, detection = Detection.Detecting)
            startUnlessRunning(a!!, target)
        }
    }

    /** Caller holds [lock]. */
    private fun startUnlessRunning(a: ActiveServer, target: CapabilityTarget) {
        if (running?.let { it.target == target && it.job.isActive } == true) return
        running = Running(target, scope.launch { detect(a, target) })
    }

    private suspend fun detect(a: ActiveServer, target: CapabilityTarget) {
        val outcome = try {
            probe(a)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeOutcome(Detection.Failed)
        }
        state.update { current ->
            when {
                current.target != target -> current // the target changed while this ran: drop the answer
                outcome.detection == Detection.Failed && current.detection == Detection.Done -> current
                else -> outcome.toCapabilities(target)
            }
        }
    }
}
