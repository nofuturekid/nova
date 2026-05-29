package io.github.nofuturekid.nova.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.local.ApiKeyStore
import io.github.nofuturekid.nova.data.local.SettingsStore
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Server
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
    val localCertPin: String? = null,
)

@Singleton
class ServerRepository @Inject constructor(
    private val store: SettingsStore,
    private val keys: ApiKeyStore,
) {
    val servers: Flow<List<Server>> = store.servers
    val connectionMode: Flow<ConnectionMode> = store.connectionMode

    val activeServer: Flow<Server?> = combine(store.servers, store.activeServerId) { list, id ->
        resolveActiveServer(list, id)
    }

    val activeWithKey: Flow<ActiveServer?> =
        combine(activeServer, connectionMode, store.certPins) { s, mode, pins -> Triple(s, mode, pins) }
            .map { (s, mode, pins) ->
                s?.let {
                    val result = keys.getResult(it.id)
                    ActiveServer(
                        server = it,
                        mode = mode,
                        apiKey = (result as? ApiKeyResult.Present)?.key.orEmpty(),
                        keyState = result,
                        localCertPin = pins[it.id],
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
        if (!resolved.trustSelfSignedLocal) store.clearPin(resolved.id)
        return resolved
    }

    suspend fun delete(id: String) {
        val current = store.servers.first().filter { it.id != id }
        store.setServers(current)
        keys.remove(id)
        store.clearPin(id)
        if (store.activeServerId.first() == id) {
            store.setActiveServer(nextActiveAfterDelete(current))
        }
    }

    suspend fun setActive(id: String) {
        store.setActiveServer(id)
    }

    suspend fun setConnectionMode(mode: ConnectionMode) {
        store.setConnectionMode(mode)
    }

    /** Returns the stored API key for a server, or null if none. */
    suspend fun apiKeyFor(id: String): String? = keys.get(id)

    suspend fun setLocalCertPin(serverId: String, sha256: String) = store.setPin(serverId, sha256)
    suspend fun clearLocalCertPin(serverId: String) = store.clearPin(serverId)
    suspend fun pinFor(serverId: String): String? = store.pinFor(serverId)

    companion object {
        /**
         * Resolves the active [Server] from the stored list + persisted
         * active id. The well-defined "no server" representation is `null`
         * (mirrored downstream by [activeWithKey] → `null` →
         * [UnraidRepository.domainStream] → [DomainState.NoServer]).
         *
         * Used by the production [activeServer] flow; also the seam the
         * triage-#20 unit test asserts against, so there is exactly ONE
         * implementation of the resolution rule (Rule 9 — a copy could
         * drift and the test would stop pinning the real behaviour).
         */
        internal fun resolveActiveServer(list: List<Server>, id: String?): Server? =
            list.firstOrNull { it.id == id } ?: list.firstOrNull()

        /**
         * The active-server id to persist after a [delete] removes the
         * currently-active server, given the remaining (post-filter) list.
         * When the deleted server was the ONLY/last one, `remaining` is
         * empty and this is `null` — i.e. `setActiveServer(null)`, the
         * well-defined "no active server" state (triage #20). When other
         * servers remain, the first survivor becomes active so the app is
         * never left pointing at a deleted server.
         *
         * Test-only seam — same-module `internal`, mirroring the existing
         * `internal` unit-test entrypoint convention here (see
         * [UnraidRepository.Companion.pollDomainForTest] /
         * [io.github.nofuturekid.nova.data.update.UpdateRepository.parseVersion]).
         * Production [delete] calls it; the triage-#20 test asserts it so
         * the real reset decision is pinned, not a re-derived copy.
         */
        internal fun nextActiveAfterDelete(remaining: List<Server>): String? =
            remaining.firstOrNull()?.id
    }
}
