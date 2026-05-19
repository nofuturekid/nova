package net.unraidcontrol.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.unraidcontrol.app.data.local.ApiKeyResult
import net.unraidcontrol.app.data.local.ApiKeyStore
import net.unraidcontrol.app.data.local.SettingsStore
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.Server
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveServer(
    val server: Server,
    val mode: ConnectionMode,
    val apiKey: String,
    /**
     * Why [apiKey] is blank, when it is (ADR-0035). [ApiKeyResult.Absent]
     * → no key stored (existing "missing key" path);
     * [ApiKeyResult.Undecryptable] → ciphertext present but un-decryptable
     * (distinct re-enter prompt); [ApiKeyResult.Present] → key usable.
     */
    val keyState: ApiKeyResult,
)

@Singleton
class ServerRepository @Inject constructor(
    private val store: SettingsStore,
    private val keys: ApiKeyStore,
) {
    val servers: Flow<List<Server>> = store.servers
    val connectionMode: Flow<ConnectionMode> = store.connectionMode

    val activeServer: Flow<Server?> = combine(store.servers, store.activeServerId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    val activeWithKey: Flow<ActiveServer?> =
        combine(activeServer, connectionMode) { s, mode -> s to mode }.map { (s, mode) ->
            s?.let {
                val result = keys.getResult(it.id)
                ActiveServer(
                    server = it,
                    mode = mode,
                    apiKey = (result as? ApiKeyResult.Present)?.key.orEmpty(),
                    keyState = result,
                )
            }
        }

    suspend fun upsert(input: Server, apiKey: String): Server {
        val current = store.servers.first()
        val resolved = if (input.id.isBlank()) input.copy(id = UUID.randomUUID().toString()) else input
        val next = current.filter { it.id != resolved.id } + resolved
        store.setServers(next)
        if (apiKey.isNotBlank()) {
            keys.put(resolved.id, apiKey)
        }
        if (store.activeServerId.first() == null) store.setActiveServer(resolved.id)
        return resolved
    }

    suspend fun delete(id: String) {
        val current = store.servers.first().filter { it.id != id }
        store.setServers(current)
        keys.remove(id)
        if (store.activeServerId.first() == id) store.setActiveServer(current.firstOrNull()?.id)
    }

    suspend fun setActive(id: String) {
        store.setActiveServer(id)
    }

    suspend fun setConnectionMode(mode: ConnectionMode) {
        store.setConnectionMode(mode)
    }

    /** Returns the stored API key for a server, or null if none. */
    suspend fun apiKeyFor(id: String): String? = keys.get(id)
}
