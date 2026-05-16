package net.unraidcontrol.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.exception.ApolloException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import net.unraidcontrol.app.data.api.ApolloClientFactory
import net.unraidcontrol.app.data.api.toArrayInfo
import net.unraidcontrol.app.data.api.toContainers
import net.unraidcontrol.app.data.api.toLiveMetrics
import net.unraidcontrol.app.data.api.toNotifications
import net.unraidcontrol.app.data.api.toServerInfo
import net.unraidcontrol.app.data.api.toVms
import net.unraidcontrol.app.data.model.ArrayInfo
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.Container
import net.unraidcontrol.app.data.model.LiveMetrics
import net.unraidcontrol.app.data.model.LogLine
import net.unraidcontrol.app.data.model.Notifications
import net.unraidcontrol.app.data.model.ServerInfo
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.graphql.FetchContainerLogsQuery
import net.unraidcontrol.app.graphql.GetNotificationsQuery
import net.unraidcontrol.app.graphql.NotificationsFeedSubscription
import net.unraidcontrol.app.graphql.ForceStopVmMutation
import net.unraidcontrol.app.graphql.GetArrayQuery
import net.unraidcontrol.app.graphql.GetDockerContainersQuery
import net.unraidcontrol.app.graphql.GetMetricsQuery
import net.unraidcontrol.app.graphql.GetServerInfoQuery
import net.unraidcontrol.app.graphql.CancelParityCheckMutation
import net.unraidcontrol.app.graphql.GetVmsQuery
import net.unraidcontrol.app.graphql.PauseContainerMutation
import net.unraidcontrol.app.graphql.PauseParityCheckMutation
import net.unraidcontrol.app.graphql.PauseVmMutation
import net.unraidcontrol.app.graphql.PingQuery
import net.unraidcontrol.app.graphql.RebootVmMutation
import net.unraidcontrol.app.graphql.ResetVmMutation
import net.unraidcontrol.app.graphql.ResumeParityCheckMutation
import net.unraidcontrol.app.graphql.ResumeVmMutation
import net.unraidcontrol.app.graphql.StartArrayMutation
import net.unraidcontrol.app.graphql.StartContainerMutation
import net.unraidcontrol.app.graphql.StartParityCheckMutation
import net.unraidcontrol.app.graphql.StartVmMutation
import net.unraidcontrol.app.graphql.StopArrayMutation
import net.unraidcontrol.app.graphql.StopContainerMutation
import net.unraidcontrol.app.graphql.UpdateAllContainersMutation
import net.unraidcontrol.app.graphql.UpdateContainerMutation
import net.unraidcontrol.app.graphql.StopVmMutation
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnraidRepository @Inject constructor(
    private val servers: ServerRepository,
    private val apolloFactory: ApolloClientFactory,
) {
    private companion object {
        /** Default poll interval per domain (ms). Values reflect how often
         *  each domain actually changes — info almost never, metrics often. */
        const val POLL_INFO_MS = 60_000L      // hostname/version/CPU specs change between reboots
        const val POLL_METRICS_MS = 2_000L    // CPU/memory sparklines want a smooth refresh
        const val POLL_ARRAY_MS = 5_000L      // disk states / parity-check progress
        const val POLL_DOCKER_MS = 2_000L     // container start/stop/update reactions
        const val POLL_VMS_MS = 3_000L        // VM state transitions
        const val POLL_NOTIFICATIONS_MS = 60_000L  // alerts change rarely; bell is global

        /** Consecutive poll failures before the UI drops to an Error state. */
        const val TRANSIENT_ERROR_TOLERANCE = 3
    }

    // ── Domain polling streams ────────────────────────────────────────
    //
    // Each is a cold Flow keyed off the active server + connection mode.
    // The Flow polls only while it has at least one subscriber — when the
    // ViewModel un-subscribes (because the corresponding tab isn't visible
    // or the app is backgrounded), the polling loop suspends automatically.

    fun infoStream(intervalMs: Long = POLL_INFO_MS): Flow<DomainState<ServerInfo>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetServerInfoQuery()) { data -> data.toServerInfo() }.withBaseUrl(baseUrl)
        }

    fun metricsStream(intervalMs: Long = POLL_METRICS_MS): Flow<DomainState<LiveMetrics>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetMetricsQuery()) { data -> data.toLiveMetrics() }.withBaseUrl(baseUrl)
        }

    fun arrayStream(intervalMs: Long = POLL_ARRAY_MS): Flow<DomainState<ArrayInfo>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetArrayQuery()) { data -> data.toArrayInfo() }.withBaseUrl(baseUrl)
        }

    fun dockerStream(intervalMs: Long = POLL_DOCKER_MS): Flow<DomainState<List<Container>>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetDockerContainersQuery()) { data -> data.toContainers() }.withBaseUrl(baseUrl)
        }

    fun vmsStream(intervalMs: Long = POLL_VMS_MS): Flow<DomainState<List<Vm>>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetVmsQuery()) { data -> data.toVms() }.withBaseUrl(baseUrl)
        }

    /**
     * E1 pilot (ADR-0026): the bell badge + sheet list are driven by a
     * GraphQL subscription, with the 60 s [GetNotificationsQuery] poll as
     * the automatic fallback when the WS session can't be established
     * (older API versions / a proxy that doesn't pass the WS upgrade).
     */
    fun notificationsStream(intervalMs: Long = POLL_NOTIFICATIONS_MS): Flow<DomainState<Notifications>> =
        subscriptionStream(
            intervalMs = intervalMs,
            poll = { client, baseUrl ->
                fetch(client, GetNotificationsQuery()) { data -> data.toNotifications() }
                    .withBaseUrl(baseUrl)
            },
            subscribe = { client ->
                client.subscription(NotificationsFeedSubscription()).toFlow().map { resp ->
                    when {
                        resp.exception != null -> throw resp.exception!!
                        resp.hasErrors() -> DomainState.Error(
                            resp.errors?.joinToString { it.message } ?: "GraphQL subscription error"
                        )
                        resp.data == null -> DomainState.Error("Empty subscription payload")
                        else -> DomainState.Content(resp.data!!.toNotifications())
                    }
                }
            },
        )

    /**
     * Generic per-domain polling loop.
     *
     * Resilience: once we have a successful Content, we don't downgrade to
     * Error on every transient failure. The last good Content is re-emitted
     * for up to [TRANSIENT_ERROR_TOLERANCE] consecutive failures, only after
     * which an Error replaces it. Reset on the next success.
     */
    private fun <T> domainStream(
        intervalMs: Long,
        fetch: suspend (ApolloClient, String) -> DomainState<T>,
    ): Flow<DomainState<T>> = servers.activeWithKey
        .distinctUntilChanged()
        .flatMapLatest { active ->
            flow {
                if (active == null) {
                    emit(DomainState.NoServer)
                    return@flow
                }
                if (active.apiKey.isBlank()) {
                    emit(DomainState.Error("Missing API key for ${active.server.name}"))
                    return@flow
                }
                val url = activeUrl(active)
                val client = apolloFactory.build(url, active.apiKey)
                pollLoop(client, url, intervalMs, fetch)
            }
        }

    /**
     * Phase E (ADR-0026): subscription-backed domain stream with a hard
     * fallback to polling. Tries the WS subscription first; if it never
     * connects, errors, or the server closes it, drops into the exact
     * same [pollLoop] resilience the polling streams use — so an API
     * version without `type Subscription` (or a proxy without WS
     * upgrade) degrades to the prior behaviour instead of breaking.
     * App-visible gating is unchanged: this is composed under the same
     * `gatedStream` as every other domain in MainViewModel.
     */
    private fun <T> subscriptionStream(
        intervalMs: Long,
        poll: suspend (ApolloClient, String) -> DomainState<T>,
        subscribe: (ApolloClient) -> Flow<DomainState<T>>,
    ): Flow<DomainState<T>> = servers.activeWithKey
        .distinctUntilChanged()
        .flatMapLatest { active ->
            flow {
                if (active == null) {
                    emit(DomainState.NoServer)
                    return@flow
                }
                if (active.apiKey.isBlank()) {
                    emit(DomainState.Error("Missing API key for ${active.server.name}"))
                    return@flow
                }
                val url = activeUrl(active)
                val client = apolloFactory.build(url, active.apiKey)
                try {
                    subscribe(client).collect { emit(it.withBaseUrl(url)) }
                    // Server closed the stream cleanly — keep data fresh via poll.
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // gate closed / active server switched — honour it
                } catch (_: Throwable) {
                    // WS unsupported / dropped / auth rejected — degrade.
                }
                pollLoop(client, url, intervalMs, poll)
            }
        }

    /** Shared resilience loop (see [domainStream] kdoc). Runs until the
     *  collecting coroutine is cancelled. */
    private suspend fun <T> FlowCollector<DomainState<T>>.pollLoop(
        client: ApolloClient,
        url: String,
        intervalMs: Long,
        fetch: suspend (ApolloClient, String) -> DomainState<T>,
    ) {
        var lastContent: DomainState.Content<T>? = null
        var consecutiveErrors = 0
        while (true) {
            when (val result = fetch(client, url)) {
                is DomainState.Content<T> -> {
                    lastContent = result
                    consecutiveErrors = 0
                    emit(result)
                }
                is DomainState.Error -> {
                    consecutiveErrors++
                    val tolerated = lastContent != null &&
                        consecutiveErrors < TRANSIENT_ERROR_TOLERANCE
                    emit(if (tolerated) lastContent else result)
                }
                else -> emit(result)
            }
            delay(intervalMs)
        }
    }

    /** Single Apollo query → DomainState wrapper. The base URL is filled in
     *  by [withBaseUrl] after the call so we keep [fetch] generic. */
    private suspend fun <D : Query.Data, T> fetch(
        client: ApolloClient,
        query: Query<D>,
        map: (D) -> T,
    ): DomainState<T> = try {
        val resp = client.query(query).execute()
        when {
            resp.hasErrors() -> DomainState.Error(
                resp.errors?.joinToString { it.message } ?: "Unknown GraphQL error"
            )
            resp.data == null -> DomainState.Error("Empty GraphQL response")
            else -> DomainState.Content(map(resp.data!!))
        }
    } catch (e: ApolloException) {
        DomainState.Error(e.message ?: "Network error")
    } catch (e: Exception) {
        DomainState.Error(e.message ?: "Unexpected error")
    }

    private fun <T> DomainState<T>.withBaseUrl(baseUrl: String): DomainState<T> = when (this) {
        is DomainState.Content<T> -> DomainState.Content(value, baseUrl)
        else -> this
    }

    // ── One-shot helpers (refresh, ping, logs) ────────────────────────

    /** Force a single fetch of every domain. Used by pull-to-refresh.
     *  Doesn't return data — the live streams pick up the new values. */
    suspend fun refreshAll() {
        val active = servers.activeWithKey.first() ?: return
        if (active.apiKey.isBlank()) return
        val url = activeUrl(active)
        val client = apolloFactory.build(url, active.apiKey)
        // Fire all five queries in sequence; errors are ignored — the polling
        // streams will surface them on their own cadence.
        runCatching { client.query(GetServerInfoQuery()).execute() }
        runCatching { client.query(GetMetricsQuery()).execute() }
        runCatching { client.query(GetArrayQuery()).execute() }
        runCatching { client.query(GetDockerContainersQuery()).execute() }
        runCatching { client.query(GetVmsQuery()).execute() }
    }

    private fun activeUrl(active: ActiveServer): String = when (active.mode) {
        ConnectionMode.Local  -> active.server.localUrl
        ConnectionMode.Remote -> active.server.remoteUrl.ifBlank { active.server.localUrl }
    }

    private fun buildClient(active: ActiveServer): ApolloClient =
        apolloFactory.build(activeUrl(active), active.apiKey)

    private fun buildLongRunningClient(active: ActiveServer): ApolloClient =
        apolloFactory.buildLongRunning(activeUrl(active), active.apiKey)

    // ── Mutations ─────────────────────────────────────────────────────
    private suspend fun activeClient(): ApolloClient? {
        val active = servers.activeWithKey.first() ?: return null
        if (active.apiKey.isBlank()) return null
        return buildClient(active)
    }

    /** Same as [activeClient] but with the long-running timeouts (10 min
     *  read/write). Use for mutations that pull images or otherwise spin
     *  server-side work for minutes. See ADR-0016. */
    private suspend fun activeLongRunningClient(): ApolloClient? {
        val active = servers.activeWithKey.first() ?: return null
        if (active.apiKey.isBlank()) return null
        return buildLongRunningClient(active)
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

    /** Pulls the latest image and recreates the container. Server-side work
     *  routinely takes 30 s – several minutes, so this routes through the
     *  long-running Apollo client (ADR-0016). Caller (the ViewModel) marks
     *  the container as updating before invoking and clears the mark in
     *  finally — see [MainViewModel.updateContainer]. */
    suspend fun updateContainer(id: String) {
        activeLongRunningClient()?.mutation(UpdateContainerMutation(id))?.execute()
    }

    /** Updates every container that the server reports as having an update
     *  available. Same long-running plumbing as [updateContainer]; expect
     *  the HTTP connection to stay open until the *last* container in the
     *  batch has finished its pull + recreate. */
    suspend fun updateAllContainers() {
        activeLongRunningClient()?.mutation(UpdateAllContainersMutation())?.execute()
    }

    suspend fun startVm(id: String)  { activeClient()?.mutation(StartVmMutation(id))?.execute() }
    suspend fun stopVm(id: String, force: Boolean = false) {
        val c = activeClient() ?: return
        if (force) c.mutation(ForceStopVmMutation(id)).execute()
        else       c.mutation(StopVmMutation(id)).execute()
    }
    suspend fun pauseVm(id: String)  { activeClient()?.mutation(PauseVmMutation(id))?.execute() }
    suspend fun resumeVm(id: String) { activeClient()?.mutation(ResumeVmMutation(id))?.execute() }
    suspend fun rebootVm(id: String) { activeClient()?.mutation(RebootVmMutation(id))?.execute() }
    suspend fun resetVm(id: String)  { activeClient()?.mutation(ResetVmMutation(id))?.execute() }

    suspend fun startParityCheck(correct: Boolean) {
        activeClient()?.mutation(StartParityCheckMutation(correct))?.execute()
    }
    suspend fun pauseParityCheck()  { activeClient()?.mutation(PauseParityCheckMutation())?.execute() }
    suspend fun resumeParityCheck() { activeClient()?.mutation(ResumeParityCheckMutation())?.execute() }
    suspend fun cancelParityCheck() { activeClient()?.mutation(CancelParityCheckMutation())?.execute() }

    /**
     * Fetches the last [tail] log lines for the given container. Returns an
     * empty list if no active server, no key, or the request fails — the UI
     * can distinguish "no logs" from "still loading" via its own state.
     */
    suspend fun containerLogs(id: String, tail: Int = 200): List<LogLine> {
        val client = activeClient() ?: return emptyList()
        return try {
            val resp = client.query(
                FetchContainerLogsQuery(id = id, tail = tail),
            ).execute()
            resp.data?.docker?.logs?.lines.orEmpty().map { line ->
                LogLine(time = line.timestamp, message = line.message)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Pings the server with the minimal possible GraphQL query (`{ __typename }`).
     * Confirms TCP reachability, TLS handshake, auth header acceptance and that
     * the endpoint is actually GraphQL — without depending on whether our
     * hand-written schema matches the live server's field names.
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
