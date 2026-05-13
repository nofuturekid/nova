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
import net.unraidcontrol.app.graphql.FetchContainerLogsQuery
import net.unraidcontrol.app.graphql.ForceStopVmMutation
import net.unraidcontrol.app.graphql.GetServerSnapshotQuery
import net.unraidcontrol.app.graphql.PauseContainerMutation
import net.unraidcontrol.app.graphql.PauseVmMutation
import net.unraidcontrol.app.graphql.PingQuery
import com.apollographql.apollo.api.Optional
import net.unraidcontrol.app.data.model.LogLine
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
    private companion object {
        /** Number of consecutive poll failures before the UI shows an error
         *  screen instead of the last successful snapshot. At pollMs=2000 this
         *  is roughly a 6-second tolerance for transient network hiccups. */
        const val TRANSIENT_ERROR_TOLERANCE = 3
    }

    /**
     * Polled snapshot flow keyed off the active server + connection mode.
     *
     * Resilience: once we have a successful snapshot, we don't downgrade the
     * UI to an Error state on every transient poll failure. The last good
     * Content is re-emitted instead, until [TRANSIENT_ERROR_TOLERANCE]
     * consecutive failures suggest the server is actually unreachable.
     */
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
                    val url = activeUrl(active)
                    val client = apolloFactory.build(url, active.apiKey)
                    emit(SnapshotState.Loading)

                    var lastContent: SnapshotState.Content? = null
                    var consecutiveErrors = 0
                    while (true) {
                        val result = fetch(client, url)
                        when (result) {
                            is SnapshotState.Content -> {
                                lastContent = result
                                consecutiveErrors = 0
                                emit(result)
                            }
                            is SnapshotState.Error -> {
                                consecutiveErrors++
                                val tolerated = lastContent != null &&
                                    consecutiveErrors < TRANSIENT_ERROR_TOLERANCE
                                emit(if (tolerated) lastContent!! else result)
                            }
                            else -> emit(result)
                        }
                        delay(pollMs)
                    }
                }
            }

    suspend fun snapshotOnce(): SnapshotState {
        val active = servers.activeWithKey.first() ?: return SnapshotState.NoServer
        if (active.apiKey.isBlank()) return SnapshotState.Error("Missing API key")
        val url = activeUrl(active)
        return fetch(apolloFactory.build(url, active.apiKey), url)
    }

    private fun activeUrl(active: ActiveServer): String = when (active.mode) {
        ConnectionMode.Local  -> active.server.localUrl
        ConnectionMode.Remote -> active.server.remoteUrl.ifBlank { active.server.localUrl }
    }

    private fun buildClient(active: ActiveServer): ApolloClient =
        apolloFactory.build(activeUrl(active), active.apiKey)

    private suspend fun fetch(client: ApolloClient, baseUrl: String): SnapshotState = try {
        val resp = client.query(GetServerSnapshotQuery()).execute()
        if (resp.hasErrors()) {
            SnapshotState.Error(resp.errors?.joinToString { it.message } ?: "Unknown GraphQL error")
        } else {
            SnapshotState.Content(resp.data!!.toSnapshot(serverBaseUrl = baseUrl))
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
    /** Unraid 7's docker mutations have no atomic `restart` — emulate as stop+start. */
    suspend fun restartContainer(id: String) {
        val c = activeClient() ?: return
        c.mutation(StopContainerMutation(id)).execute()
        c.mutation(StartContainerMutation(id)).execute()
    }
    suspend fun pauseContainer(id: String)   { activeClient()?.mutation(PauseContainerMutation(id))?.execute() }

    suspend fun startVm(id: String)  { activeClient()?.mutation(StartVmMutation(id))?.execute() }
    suspend fun stopVm(id: String, force: Boolean = false) {
        val c = activeClient() ?: return
        if (force) c.mutation(ForceStopVmMutation(id)).execute()
        else       c.mutation(StopVmMutation(id)).execute()
    }
    suspend fun pauseVm(id: String)  { activeClient()?.mutation(PauseVmMutation(id))?.execute() }
    suspend fun resumeVm(id: String) { activeClient()?.mutation(ResumeVmMutation(id))?.execute() }

    /**
     * Fetches the last [tail] log lines for the given container. Returns an
     * empty list if no active server, no key, or the request fails — the UI
     * can distinguish "no logs" from "still loading" via its own state.
     */
    suspend fun containerLogs(id: String, tail: Int = 200): List<LogLine> {
        val client = activeClient() ?: return emptyList()
        return try {
            val resp = client.query(
                FetchContainerLogsQuery(id = id, tail = Optional.present(tail)),
            ).execute()
            resp.data?.docker?.logs?.lines.orEmpty().map { line ->
                LogLine(time = line.timestamp, message = line.message)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One-shot ping. Returns null on success or a message on failure. */
    /**
     * Pings the server with the minimal possible GraphQL query (`{ __typename }`).
     * Confirms TCP reachability, TLS handshake, auth header acceptance and that the
     * endpoint is actually GraphQL — without depending on whether our hand-written
     * schema matches the live server's field names.
     */
    suspend fun testConnection(baseUrl: String, apiKey: String): String? = try {
        val resp = apolloFactory.build(baseUrl, apiKey)
            .query(PingQuery()).execute()
        when {
            resp.hasErrors() -> resp.errors?.firstOrNull()?.message ?: "GraphQL error"
            resp.data == null -> "Empty GraphQL response"
            else -> null
        }
    } catch (e: ApolloException) { e.message ?: "Network error" }
      catch (e: Exception) { e.message ?: "Unknown error" }
}
