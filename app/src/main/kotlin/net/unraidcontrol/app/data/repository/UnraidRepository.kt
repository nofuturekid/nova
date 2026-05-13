package net.unraidcontrol.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.ApolloException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import net.unraidcontrol.app.data.api.ApolloClientFactory
import net.unraidcontrol.app.data.api.toSnapshot
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.ServerSnapshot
import net.unraidcontrol.app.graphql.GetServerSnapshotQuery
import net.unraidcontrol.app.graphql.PauseContainerMutation
import net.unraidcontrol.app.graphql.PauseVmMutation
import net.unraidcontrol.app.graphql.RestartContainerMutation
import net.unraidcontrol.app.graphql.ResumeVmMutation
import net.unraidcontrol.app.graphql.StartArrayMutation
import net.unraidcontrol.app.graphql.StartContainerMutation
import net.unraidcontrol.app.graphql.StartVmMutation
import net.unraidcontrol.app.graphql.StopArrayMutation
import net.unraidcontrol.app.graphql.StopContainerMutation
import net.unraidcontrol.app.graphql.StopVmMutation
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SnapshotState {
    data object Loading : SnapshotState
    data class Content(val snapshot: ServerSnapshot) : SnapshotState
    data class Error(val message: String) : SnapshotState
    data object NoServer : SnapshotState
}

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnraidRepository @Inject constructor(
    private val servers: ServerRepository,
    private val apolloFactory: ApolloClientFactory,
) {
    /** Polled snapshot flow keyed off the active server + connection mode. */
    fun snapshotStream(pollMs: Long = 2000L): Flow<SnapshotState> =
        servers.activeWithKey
            .distinctUntilChanged()
            .flatMapLatest { active ->
                flow {
                    if (active == null) {
                        emit(SnapshotState.NoServer)
                        return@flow
                    }
                    if (active.apiKey.isBlank()) {
                        emit(SnapshotState.Error("Missing API key for ${active.server.name}"))
                        return@flow
                    }
                    val client = buildClient(active)
                    emit(SnapshotState.Loading)
                    while (true) {
                        emit(fetch(client))
                        delay(pollMs)
                    }
                }
            }

    suspend fun snapshotOnce(): SnapshotState {
        val active = servers.activeWithKey.first() ?: return SnapshotState.NoServer
        if (active.apiKey.isBlank()) return SnapshotState.Error("Missing API key")
        return fetch(buildClient(active))
    }

    private fun buildClient(active: ActiveServer): ApolloClient {
        val url = when (active.mode) {
            ConnectionMode.Local  -> active.server.localUrl
            ConnectionMode.Remote -> active.server.remoteUrl.ifBlank { active.server.localUrl }
        }
        return apolloFactory.build(url, active.apiKey)
    }

    private suspend fun fetch(client: ApolloClient): SnapshotState = try {
        val resp = client.query(GetServerSnapshotQuery()).execute()
        if (resp.hasErrors()) {
            SnapshotState.Error(resp.errors?.joinToString { it.message } ?: "Unknown GraphQL error")
        } else {
            SnapshotState.Content(resp.data!!.toSnapshot())
        }
    } catch (e: ApolloException) {
        SnapshotState.Error(e.message ?: "Network error")
    } catch (e: Exception) {
        SnapshotState.Error(e.message ?: "Unexpected error")
    }

    // ── Mutations ─────────────────────────────────────────────────
    private suspend fun activeClient(): ApolloClient? {
        val active = servers.activeWithKey.first() ?: return null
        if (active.apiKey.isBlank()) return null
        return buildClient(active)
    }

    suspend fun startArray() { activeClient()?.mutation(StartArrayMutation())?.execute() }
    suspend fun stopArray()  { activeClient()?.mutation(StopArrayMutation())?.execute() }

    suspend fun startContainer(id: String)   { activeClient()?.mutation(StartContainerMutation(id))?.execute() }
    suspend fun stopContainer(id: String)    { activeClient()?.mutation(StopContainerMutation(id))?.execute() }
    suspend fun restartContainer(id: String) { activeClient()?.mutation(RestartContainerMutation(id))?.execute() }
    suspend fun pauseContainer(id: String)   { activeClient()?.mutation(PauseContainerMutation(id))?.execute() }

    suspend fun startVm(id: String)  { activeClient()?.mutation(StartVmMutation(id))?.execute() }
    suspend fun stopVm(id: String, force: Boolean = false) {
        activeClient()?.mutation(StopVmMutation(id, force = force))?.execute()
    }
    suspend fun pauseVm(id: String)  { activeClient()?.mutation(PauseVmMutation(id))?.execute() }
    suspend fun resumeVm(id: String) { activeClient()?.mutation(ResumeVmMutation(id))?.execute() }

    /** One-shot ping. Returns null on success or a message on failure. */
    suspend fun testConnection(baseUrl: String, apiKey: String): String? = try {
        val resp = apolloFactory.build(baseUrl, apiKey)
            .query(GetServerSnapshotQuery()).execute()
        when {
            resp.hasErrors()       -> resp.errors?.firstOrNull()?.message ?: "GraphQL error"
            resp.data == null      -> "Empty response"
            else                   -> null
        }
    } catch (e: ApolloException) { e.message ?: "Network error" }
      catch (e: Exception) { e.message ?: "Unknown error" }
}
