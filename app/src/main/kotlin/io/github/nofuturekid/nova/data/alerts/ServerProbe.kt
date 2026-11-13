package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Server

/**
 * The one-shot seam: fetch what one check needs from one server, once, and say how it went.
 * The background worker will call it per server; a home-screen widget can reuse it.
 *
 * Only the interface exists so far. The real implementation (two existing read-only queries,
 * fail-closed certificate trust, no key reset, no prompts) belongs to the worker change.
 * [pin] is the pinned local certificate fingerprint of [server], if any.
 */
interface ServerProbe {
    suspend fun probe(server: Server, pin: String?): ProbeResult
}

sealed interface ProbeResult {
    /** The server answered. Each half is null when its own query failed. */
    data class Ok(val notifications: Notifications?, val array: ArrayInfo?) : ProbeResult
    data object Unreachable : ProbeResult

    /** No https address: not checked in the background, the connection is not encrypted. */
    data object NotEncrypted : ProbeResult
    data object CertificateProblem : ProbeResult
    data object KeyMissing : ProbeResult
    data object KeyUndecryptable : ProbeResult
    data class Rejected(val message: String) : ProbeResult
}

/**
 * What [evaluateAlerts] gets for a result. Anything but [ProbeResult.Ok] loaded nothing,
 * so it changes no state and posts nothing: failures are never alerts.
 */
val ProbeResult.loadedNotifications: Notifications? get() = (this as? ProbeResult.Ok)?.notifications

/** See [loadedNotifications]. */
val ProbeResult.loadedArray: ArrayInfo? get() = (this as? ProbeResult.Ok)?.array
