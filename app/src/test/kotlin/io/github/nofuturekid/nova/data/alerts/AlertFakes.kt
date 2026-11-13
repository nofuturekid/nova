package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.Server

/** In-memory [AlertStateStore] for tests of the code that will use the store. */
class InMemoryAlertStateStore : AlertStateStore {
    private val states = mutableMapOf<String, ServerAlertState>()

    override suspend fun get(serverId: String): ServerAlertState = states[serverId] ?: ServerAlertState()
    override suspend fun put(serverId: String, state: ServerAlertState) { states[serverId] = state }
    override suspend fun remove(serverId: String) { states.remove(serverId) }
    override suspend fun clear() { states.clear() }

    val serverIds: Set<String> get() = states.keys.toSet()
}

/** [ServerProbe] that answers from a script: the result set for a server id, [ProbeResult.Unreachable] otherwise. */
class FakeServerProbe(private val results: MutableMap<String, ProbeResult> = mutableMapOf()) : ServerProbe {
    val probed = mutableListOf<Pair<String, String?>>()

    fun answer(serverId: String, result: ProbeResult) { results[serverId] = result }

    override suspend fun probe(server: Server, pin: String?): ProbeResult {
        probed += server.id to pin
        return results[server.id] ?: ProbeResult.Unreachable
    }
}
