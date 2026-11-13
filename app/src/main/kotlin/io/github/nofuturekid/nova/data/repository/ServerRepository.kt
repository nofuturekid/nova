package io.github.nofuturekid.nova.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.local.ApiKeyStore
import io.github.nofuturekid.nova.data.local.ServerSnapshot
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
        activeWithKeyFlow(store.serverSnapshot, keys.ciphertexts, keys::getResult)

    /**
     * Stores [input]. [localCertPin] is the fingerprint the user accepted for it; it is
     * written atomically with the entry. Null keeps the stored pin; trust off drops it.
     */
    suspend fun upsert(input: Server, apiKey: String, localCertPin: String? = null): Server {
        val current = store.servers.first()
        val resolved = if (input.id.isBlank()) input.copy(id = UUID.randomUUID().toString()) else input
        val next = current.filter { it.id != resolved.id } + resolved
        // Key first: if it cannot be stored nothing was written, so a retry cannot
        // leave a duplicate server. (A key-only change reaches running streams via
        // the key store's flow in activeWithKey, not via the list write.)
        if (apiKey.isNotBlank()) {
            keys.put(resolved.id, apiKey)
        }
        // List, pin and first-server activation in one write: the pin the user accepted
        // must exist the moment the entry can become active, or the streams would raise
        // the certificate prompt a second time (#292). Trust off drops any pin.
        store.saveServer(next, resolved.id, pin = localCertPin, dropPin = !resolved.trustSelfSignedLocal)
        return resolved
    }

    /**
     * Adds the demo entry (ADR-0050) and makes it the active server. No key is stored and no
     * address exists; the entry has a fixed id, so entering twice does not add a second one.
     */
    suspend fun enterDemo() {
        upsert(DemoFixtures.server, apiKey = "")
        store.setActiveServer(DemoFixtures.SERVER_ID)
    }

    suspend fun delete(id: String) {
        val current = store.servers.first().filter { it.id != id }
        store.setServers(current)
        keys.remove(id)
        store.clearPin(id)
        store.clearAlertState(id)
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

    /** Explicit recovery: deletes ALL stored API keys and the key material (ADR-0035). */
    suspend fun resetKeyStorage() = keys.resetKeyStorage()

    /** Returns the stored API key for a server, or null if none. */
    suspend fun apiKeyFor(id: String): String? = keys.get(id)

    suspend fun setLocalCertPin(serverId: String, sha256: String) = store.setPin(serverId, sha256)
    suspend fun clearLocalCertPin(serverId: String) = store.clearPin(serverId)
    suspend fun pinFor(serverId: String): String? = store.pinFor(serverId)

    companion object {
        /**
         * Active server + its key. [snapshot] carries list, active id, mode and pins from one
         * DataStore read, so a server is never paired with the pins of an older snapshot.
         * [keyChanges] is part of the combine so a key-only change (list unchanged,
         * DataStore skips the write) still re-emits. Internal for the seam test.
         */
        internal fun activeWithKeyFlow(
            snapshot: Flow<ServerSnapshot>,
            keyChanges: Flow<*>,
            readKey: suspend (String) -> ApiKeyResult,
        ): Flow<ActiveServer?> =
            combine(snapshot, keyChanges) { snap, _ -> snap }
                .map { snap ->
                    resolveActiveServer(snap.servers, snap.activeId)?.let {
                        val result = readKey(it.id)
                        ActiveServer(
                            server = it,
                            mode = snap.mode,
                            apiKey = (result as? ApiKeyResult.Present)?.key.orEmpty(),
                            keyState = result,
                            localCertPin = snap.pins[it.id],
                        )
                    }
                }

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
