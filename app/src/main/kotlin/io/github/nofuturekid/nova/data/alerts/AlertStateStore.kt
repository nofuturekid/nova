package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.local.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The remembered [ServerAlertState] per server, keyed by the server's stable id.
 * A server without an entry has the empty state, which means "not baselined yet".
 */
interface AlertStateStore {
    suspend fun get(serverId: String): ServerAlertState
    suspend fun put(serverId: String, state: ServerAlertState)

    /** Forgets one server, for example when its entry is deleted. */
    suspend fun remove(serverId: String)

    /** Forgets every server, for example when alerts are switched off, so the next start baselines afresh. */
    suspend fun clear()
}

/** Persists the state in the app's DataStore, so it survives process death. */
@Singleton
class SettingsAlertStateStore @Inject constructor(
    private val store: SettingsStore,
) : AlertStateStore {
    override suspend fun get(serverId: String): ServerAlertState = store.alertStateFor(serverId)
    override suspend fun put(serverId: String, state: ServerAlertState) = store.setAlertState(serverId, state)
    override suspend fun remove(serverId: String) = store.clearAlertState(serverId)
    override suspend fun clear() = store.clearAllAlertStates()
}
