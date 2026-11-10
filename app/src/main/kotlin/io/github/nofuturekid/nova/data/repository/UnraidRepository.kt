package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.Subscription
import com.apollographql.apollo.exception.ApolloException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.nofuturekid.nova.data.api.ApolloClientFactory
import io.github.nofuturekid.nova.data.api.CertIssue
import io.github.nofuturekid.nova.data.api.TlsTrust
import io.github.nofuturekid.nova.data.api.certIssue
import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.api.combineLiveMetrics
import io.github.nofuturekid.nova.data.api.cpuPercentTotal
import io.github.nofuturekid.nova.data.api.toArrayInfo
import io.github.nofuturekid.nova.data.api.toContainerLiveStat
import io.github.nofuturekid.nova.data.api.toContainers
import io.github.nofuturekid.nova.data.api.toDisplayThresholds
import io.github.nofuturekid.nova.data.api.toLiveMetrics
import io.github.nofuturekid.nova.data.api.toMemoryTriple
import io.github.nofuturekid.nova.data.api.toTemperature
import io.github.nofuturekid.nova.data.api.toNetworkInterfaces
import io.github.nofuturekid.nova.data.api.toNotifications
import io.github.nofuturekid.nova.data.api.toPluginOperations
import io.github.nofuturekid.nova.data.api.toPlugins
import io.github.nofuturekid.nova.data.api.toServerInfo
import io.github.nofuturekid.nova.data.api.toVms
import io.github.nofuturekid.nova.data.api.toIfaceSamples
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.DisplayThresholds
import io.github.nofuturekid.nova.data.model.LiveMetrics
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.selectThroughput
import io.github.nofuturekid.nova.data.model.LogLine
import io.github.nofuturekid.nova.data.model.NetworkInterface
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Plugin
import io.github.nofuturekid.nova.data.model.PluginInstallOperation
import io.github.nofuturekid.nova.data.model.ServerInfo
import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.graphql.DockerContainerStatsSubscription
import io.github.nofuturekid.nova.graphql.SystemMetricsCpuSubscription
import io.github.nofuturekid.nova.graphql.SystemMetricsMemorySubscription
import io.github.nofuturekid.nova.graphql.SystemMetricsNetworkSubscription
import io.github.nofuturekid.nova.graphql.SystemMetricsTemperatureSubscription
import io.github.nofuturekid.nova.graphql.ArchiveAllNotificationsMutation
import io.github.nofuturekid.nova.graphql.ArchiveNotificationMutation
import io.github.nofuturekid.nova.graphql.DeleteArchivedNotificationsMutation
import io.github.nofuturekid.nova.graphql.DeleteNotificationMutation
import io.github.nofuturekid.nova.graphql.FetchContainerLogsQuery
import io.github.nofuturekid.nova.graphql.GetDisplayQuery
import io.github.nofuturekid.nova.graphql.GetNotificationListQuery
import io.github.nofuturekid.nova.graphql.RecalculateNotificationOverviewMutation
import io.github.nofuturekid.nova.graphql.UnreadNotificationMutation
import io.github.nofuturekid.nova.graphql.ForceStopVmMutation
import io.github.nofuturekid.nova.graphql.GetArrayQuery
import io.github.nofuturekid.nova.graphql.GetDockerContainersQuery
import io.github.nofuturekid.nova.graphql.GetInstalledUnraidPluginsQuery
import io.github.nofuturekid.nova.graphql.GetMetricsQuery
import io.github.nofuturekid.nova.graphql.GetNetworkInterfacesQuery
import io.github.nofuturekid.nova.graphql.GetNetworkThroughputQuery
import io.github.nofuturekid.nova.graphql.GetPluginOperationsQuery
import io.github.nofuturekid.nova.graphql.GetPluginsQuery
import io.github.nofuturekid.nova.graphql.GetServerInfoQuery
import io.github.nofuturekid.nova.graphql.CancelParityCheckMutation
import io.github.nofuturekid.nova.graphql.GetVmsQuery
import io.github.nofuturekid.nova.graphql.PauseContainerMutation
import io.github.nofuturekid.nova.graphql.PauseParityCheckMutation
import io.github.nofuturekid.nova.graphql.PauseVmMutation
import io.github.nofuturekid.nova.graphql.PingQuery
import io.github.nofuturekid.nova.graphql.RebootVmMutation
import io.github.nofuturekid.nova.graphql.ResetVmMutation
import io.github.nofuturekid.nova.graphql.ResumeParityCheckMutation
import io.github.nofuturekid.nova.graphql.ResumeVmMutation
import io.github.nofuturekid.nova.graphql.StartArrayMutation
import io.github.nofuturekid.nova.graphql.StartContainerMutation
import io.github.nofuturekid.nova.graphql.StartParityCheckMutation
import io.github.nofuturekid.nova.graphql.StartVmMutation
import io.github.nofuturekid.nova.graphql.StopArrayMutation
import io.github.nofuturekid.nova.graphql.StopContainerMutation
import io.github.nofuturekid.nova.graphql.UpdateAllContainersMutation
import io.github.nofuturekid.nova.graphql.UpdateContainerMutation
import io.github.nofuturekid.nova.graphql.StopVmMutation
import io.github.nofuturekid.nova.graphql.type.NotificationType as GNotifType
import io.github.nofuturekid.nova.data.model.NotifType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnraidRepository @Inject constructor(
    private val servers: ServerRepository,
    private val apolloFactory: ApolloClientFactory,
) {
    companion object {
        /** Default poll interval per domain (ms). Values reflect how often
         *  each domain actually changes — info almost never, metrics often. */
        const val POLL_INFO_MS = 60_000L      // hostname/version/CPU specs change between reboots
        const val POLL_METRICS_MS = 2_000L    // CPU/memory sparklines want a smooth refresh
        const val POLL_ARRAY_MS = 5_000L      // disk states / parity-check progress
        const val POLL_DOCKER_MS = 2_000L     // container start/stop/update reactions
        const val POLL_VMS_MS = 3_000L        // VM state transitions
        const val POLL_NOTIFICATIONS_MS = 60_000L  // alerts change rarely; bell is global
        const val POLL_PLUGINS_MS = 30_000L        // plugins list rarely changes
        const val POLL_INSTALLED_UNRAID_PLUGINS_MS = 30_000L  // .plg inventory rarely changes
        const val POLL_PLUGIN_OPERATIONS_MS = 10_000L  // install jobs progress; polled only while screen open
        const val POLL_NETWORK_INTERFACES_MS = 30_000L // NIC config rarely changes
        const val NETWORK_SHAPE_RETRY_MS = 15_000L     // re-probe after an inconclusive network-shape probe

        /** Consecutive poll failures before the UI drops to an Error state. */
        const val TRANSIENT_ERROR_TOLERANCE = 3

        /** Upper bound for how long [refreshAll] waits for refreshed data. */
        const val REFRESH_MAX_WAIT_MS = 20_000L

        /**
         * Grace window before [subscriptionOrPoll] degrades a live subscription to
         * its HTTP poll fallback. Chosen at 3x [POLL_METRICS_MS] (6 s): long enough
         * that a single missed ~1 Hz frame, a brief WS reconnect, or one jittery
         * delivery does NOT yank the card onto the poll path (that would defeat the
         * live update); short enough that a genuine sustained WS outage degrades to
         * the poll within ~6 s, well under the user's annoyance threshold and never
         * leaving a blank card (ADR-0026). If [POLL_METRICS_MS] ever changes, revisit
         * this 3-cycle ratio.
         */
        const val SUB_FALLBACK_GRACE_MS = 6_000L

        /**
         * The transient-error-tolerant poll loop, factored out so it has
         * exactly ONE implementation. Production [domainStream] calls it with
         * an Apollo-backed [fetch]; [pollDomainForTest] calls it with a
         * caller-scripted [fetch]. A single shared body means the unit test
         * pins the *real* tolerance state machine (Rule 9) rather than a copy
         * that could silently drift.
         *
         * Behaviour (triage #21): after a successful [DomainState.Content] is
         * emitted, the next consecutive [DomainState.Error]s do NOT surface
         * immediately — the last good Content is re-emitted while
         * `consecutiveErrors` stays below [TRANSIENT_ERROR_TOLERANCE]. Once
         * failures reach the tolerance the Error surfaces. A single success
         * resets the counter.
         */
        private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<DomainState<T>>.pollDomain(
            intervalMs: Long,
            nudges: List<Nudge>,
            fetch: suspend () -> DomainState<T>,
        ) {
            var lastContent: DomainState.Content<T>? = null
            var consecutiveErrors = 0
            while (true) {
                // Counts are read BEFORE fetching: a nudge that lands while the
                // fetch is in flight changes the count, so the wait below ends
                // at once and the data the nudge was meant to refresh is fetched.
                val seen = nudges.map { it.count.value }
                val result = fetch()
                nudges.forEachIndexed { i, n -> n.complete(seen[i]) }
                when (result) {
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
                // Sleep the poll interval, but cut it short if a nudge
                // arrives (post-action fast refresh, pull-to-refresh). No nudge
                // source → plain delay.
                if (nudges.isEmpty()) {
                    delay(intervalMs)
                } else {
                    withTimeoutOrNull(intervalMs) {
                        combine(nudges.map { it.count }) { it.toList() }.first { it != seen }
                    }
                }
            }
        }

        /**
         * Test-only seam — same-module `internal`, mirroring the existing
         * `internal` unit-test entrypoint convention here (see
         * [io.github.nofuturekid.nova.data.update.UpdateRepository.parseVersion]).
         * Drives the *production* [pollDomain] loop with a caller-scripted
         * [fetch] so a unit test can assert the documented transient-error
         * tolerance (triage #21) against the real state machine. Not used by
         * any production code path.
         */
        internal fun <T> pollDomainForTest(
            intervalMs: Long,
            nudges: List<Nudge> = emptyList(),
            fetch: suspend () -> DomainState<T>,
        ): Flow<DomainState<T>> = flow { pollDomain(intervalMs, nudges, fetch) }

        /**
         * Runs a mutation and throws [ActionFailedException] if the response
         * carries a transport or GraphQL error. THE single way every action
         * executes: Apollo Kotlin 5's `execute()` does not throw, so a bare
         * `execute()` would report every failed action as success (ADR-0037).
         */
        internal suspend fun <D : Mutation.Data> ApolloClient.runAction(m: Mutation<D>): ApolloResponse<D> =
            mutation(m).execute().orThrow()

        /** stop, then start — a failed stop aborts before `start` is sent. */
        internal suspend fun restartContainer(client: ApolloClient, id: String) {
            client.runAction(StopContainerMutation(id))
            client.runAction(StartContainerMutation(id))
        }

        /** The logs query behind [containerLogs]; `null` = the request failed. */
        internal suspend fun fetchContainerLogs(client: ApolloClient, id: String, tail: Int): List<LogLine>? {
            val resp = try {
                client.query(FetchContainerLogsQuery(id = id, tail = tail)).execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            if (resp.exception != null || resp.hasErrors()) return null
            return resp.data?.docker?.logs?.lines.orEmpty().map { line ->
                LogLine(time = line.timestamp, message = line.message)
            }
        }

        /**
         * The notification-action error contract, extracted as a pure
         * function so the production [runNotificationAction] and the
         * triage-#19 unit test drive the *same* control flow (no stale
         * copy). This is the seam, mirroring the same-module `internal`
         * test-entrypoint convention here (e.g. [pollDomainForTest],
         * [io.github.nofuturekid.nova.data.update.UpdateRepository.parseVersion]).
         *
         * Contract (intentional — see KDoc on the actions below):
         *  - [resolveClient] null → no server: silent no-op, NO nudge;
         *  - [mutate] (the actual `*NotificationMutation`) is NOT guarded:
         *    a network failure PROPAGATES out (so the recalc + nudge below
         *    are skipped); the action→refetch convergence does not run on a
         *    failed mutation;
         *  - [recalculate] IS `runCatching`-guarded — a failed overview
         *    recalc is swallowed and the nudge still fires (the next poll +
         *    server state still reconcile);
         *  - [nudge] fires only when the mutation succeeded.
         */
        internal suspend fun <C> runNotificationActionFlow(
            resolveClient: suspend () -> C?,
            mutate: suspend (C) -> Unit,
            recalculate: suspend (C) -> Unit,
            nudge: () -> Unit,
        ) {
            val c = resolveClient() ?: return
            mutate(c)
            runCatching { recalculate(c) }
            nudge()
        }

        /**
         * Test-only seam for the subscription substrate's per-sample → [DomainState]
         * mapping. Drives the same mapping logic (frame → Content) that the production
         * [subscriptionStream] applies, without requiring Apollo types or a live WS
         * connection. Mirrors the [pollDomainForTest] convention (same-module `internal`).
         *
         * The test supplies a scripted list of frames (e.g. [IfaceSample] lists) and a
         * mapping function (e.g. [selectThroughput]); this seam emits exactly one
         * [DomainState.Content] per frame, tagged with [baseUrl], and then completes.
         */
        internal fun <S, T> subscriptionStreamForTest(
            baseUrl: String,
            frames: List<S>,
            map: (S) -> T,
        ): kotlinx.coroutines.flow.Flow<DomainState<T>> =
            kotlinx.coroutines.flow.flow {
                for (f in frames) emit(DomainState.Content(map(f), baseUrl))
            }

        /**
         * Test-only seam for the cpu+mem combine -> LiveMetrics path (mirrors
         * [subscriptionStreamForTest]). Drives the SAME `combine` + [combineMetricsStates]
         * the production [liveMetricsStream] uses, over per-domain [DomainState] legs,
         * so a unit test pins BOTH the documented Content-only-when-both-Content
         * behaviour AND the NoServer/Error/Loading precedence fold (Rule 9) without
         * Apollo or a live WS. Same-module `internal`.
         */
        internal fun metricsCombineForTest(
            cpuFrames: List<DomainState<Double>>,
            memFrames: List<DomainState<Triple<Long, Long, Long?>>>,
        ): kotlinx.coroutines.flow.Flow<DomainState<LiveMetrics>> =
            combine(cpuFrames.asFlow(), memFrames.asFlow()) { c, m -> combineMetricsStates(c, m) }

        /**
         * The cpu%+mem-triple -> LiveMetrics DomainState fold, as a companion function
         * so both production [liveMetricsStream] and [metricsCombineForTest] call the
         * SAME body (no drift). NoServer on either leg dominates; otherwise the first
         * Error surfaces; Content only when BOTH legs are Content AND their server
         * URLs match (a cross-server pairing during a switch must not emit a Content
         * tagged with one leg's stale URL); anything else is Loading.
         */
        internal fun combineMetricsStates(
            cpu: DomainState<Double>,
            mem: DomainState<Triple<Long, Long, Long?>>,
        ): DomainState<LiveMetrics> = when {
            cpu is DomainState.NoServer || mem is DomainState.NoServer -> DomainState.NoServer
            cpu is DomainState.Error -> cpu
            mem is DomainState.Error -> mem
            cpu is DomainState.Content && mem is DomainState.Content && cpu.serverBaseUrl == mem.serverBaseUrl ->
                DomainState.Content(
                    combineLiveMetrics(cpu.value, mem.value.first, mem.value.second, mem.value.third),
                    cpu.serverBaseUrl,
                )
            else -> DomainState.Loading
        }

        /**
         * Pure frame-accumulation seam for the docker-stats overlay. Each frame
         * carries ONE container (id + stats); the overlay is the GROWING map across
         * frames (update/insert by id). Folded as an IMMUTABLE copy per step
         * (`acc + (id to stats)`) so every emission is a distinct map instance — a
         * shared MutableMap would make StateFlow/Compose miss updates. Returns the
         * running snapshots (one per frame), oldest first.
         */
        internal fun accumulateStats(
            frames: List<Pair<String, ContainerLiveStats>>,
        ): List<Map<String, ContainerLiveStats>> {
            val snapshots = mutableListOf<Map<String, ContainerLiveStats>>()
            var acc = emptyMap<String, ContainerLiveStats>()
            for ((id, stats) in frames) {
                acc = acc + (id to stats)
                snapshots += acc
            }
            return snapshots
        }

        /**
         * Test-only seam mirroring [subscriptionStreamForTest] for the docker-stats
         * accumulation. Emits one [DomainState.Content] per accumulated snapshot,
         * tagged with [baseUrl], then completes. Drives the SAME [accumulateStats]
         * the production [dockerStatsStream] uses.
         */
        internal fun dockerStatsStreamForTest(
            baseUrl: String,
            frames: List<Pair<String, ContainerLiveStats>>,
        ): kotlinx.coroutines.flow.Flow<DomainState<Map<String, ContainerLiveStats>>> =
            kotlinx.coroutines.flow.flow {
                for (snapshot in accumulateStats(frames)) emit(DomainState.Content(snapshot, baseUrl))
            }

        /**
         * Outcome of probing the network-throughput operation against a server.
         * [SchemaMismatch] = the server rejected the operation's shape with a GraphQL
         * validation error (Apollo 5 parses Unraid's HTTP 400
         * `application/graphql-response+json` body into `errors`);
         * [Inconclusive] = anything else (offline, auth, cert, 5xx, a non-GraphQL 400
         * from a proxy or captive portal) — never treated as "unsupported".
         */
        internal enum class ShapeProbe { Supported, SchemaMismatch, Inconclusive }

        internal fun classifyShapeProbe(
            exception: ApolloException?,
            errors: List<Error>,
            hasData: Boolean,
        ): ShapeProbe = when {
            errors.any { e ->
                e.message.contains("Cannot query field") ||
                    e.extensions?.get("code") == "GRAPHQL_VALIDATION_FAILED"
            } -> ShapeProbe.SchemaMismatch
            exception == null && errors.isEmpty() && hasData -> ShapeProbe.Supported
            else -> ShapeProbe.Inconclusive
        }

        /**
         * Whether [key] serves `metrics.network` (Unraid API 4.35+). A server that
         * does is remembered in [supported] for the process (one probe per server,
         * not per poll). A server that does not is NOT remembered: the caller probes
         * it again on its next collection, so upgrading the API takes effect without
         * an app restart. An inconclusive probe returns null (caller retries).
         */
        internal suspend fun resolveNetworkSupport(
            supported: MutableSet<String>,
            key: String,
            probe: suspend () -> ShapeProbe,
        ): Boolean? {
            if (key in supported) return true
            return when (probe()) {
                ShapeProbe.Supported -> { supported.add(key); true }
                ShapeProbe.SchemaMismatch -> false
                ShapeProbe.Inconclusive -> null
            }
        }

        /**
         * Runs [content] only on a server that serves `metrics.network`. Unsupported:
         * emits nothing (no Error — the throughput is simply absent) and completes;
         * the next collection probes again. Inconclusive probes are retried every
         * [retryMs].
         */
        internal fun <T> networkSupportGated(
            supported: MutableSet<String>,
            key: String,
            retryMs: Long,
            probe: suspend () -> ShapeProbe,
            content: () -> Flow<DomainState<T>>,
        ): Flow<DomainState<T>> = flow {
            var ok = resolveNetworkSupport(supported, key, probe)
            while (ok == null) {
                delay(retryMs)
                ok = resolveNetworkSupport(supported, key, probe)
            }
            if (ok) emitAll(content())
        }

        /**
         * Generic subscription-with-poll-fallback combinator (ADR-0026 / ADR-0043).
         *
         * [sub] is primary: its [DomainState.Content] frames are forwarded as-is, and
         * [DomainState.NoServer] is forwarded immediately (no server at all — never
         * worth burning the grace window on a poll that also has no server). The
         * stream degrades to [poll] when the sub yields a [DomainState.Error] (or
         * [DomainState.Loading], or no first frame) held continuously for [graceMs].
         * It returns to [sub] the instant the sub recovers a [DomainState.Content]
         * frame — that recovered frame is emitted directly. [poll] is collected ONLY
         * while in the fallback state — never while the sub is healthy (no
         * double-collect: the HTTP poll must not run alongside a working WS).
         *
         * This is the NEW substrate capability over ADR-0042's bare
         * [subscriptionStream] (which has no poll fallback of its own). It is a pure [DomainState] combinator — no Apollo types, no live
         * WS — so the unit test drives this exact production body on the virtual
         * scheduler (Rule 9), with [graceMs] timed by suspending primitives only (no
         * wall-clock) for determinism.
         *
         * `internal` (same-module), not `private`, mirroring the
         * [pollDomainForTest] / [subscriptionStreamForTest] seam convention so the
         * test reaches it without a repository instance.
         */
        internal fun <T> subscriptionOrPoll(
            sub: Flow<DomainState<T>>,
            poll: Flow<DomainState<T>>,
            graceMs: Long = SUB_FALLBACK_GRACE_MS,
        ): Flow<DomainState<T>> = channelFlow {
            // Latest sub state; null until the sub's first frame. A child coroutine
            // keeps this fresh while the main body decides routing.
            val subState = MutableStateFlow<DomainState<T>?>(null)
            launch { sub.collect { subState.value = it } }

            // Route on the latest sub state. transformLatest cancels the prior branch
            // whenever subState changes: a fresh sub Content cancels any in-flight
            // poll collection (recovery), and an Error/null branch is replaced the
            // moment a new state arrives.
            subState.transformLatest { latest ->
                when (latest) {
                    is DomainState.Content<T> -> emit(latest)        // healthy: poll NOT collected
                    DomainState.NoServer -> emit(DomainState.NoServer) // no server: immediate passthrough
                    else -> {
                        // null (no first Content yet), Error, or Loading: wait out the
                        // grace window for a Content to (re)appear. On recovery, emit
                        // that Content DIRECTLY (do not rely on a transformLatest
                        // re-fire — that can drop the frame). On grace expiry, degrade
                        // to poll; collecting inside this lambda means the poll is
                        // cancelled the instant subState changes back to Content.
                        val recovered = withTimeoutOrNull(graceMs) {
                            subState.first { it is DomainState.Content<T> }
                        }
                        if (recovered != null) {
                            emit(recovered)
                        } else {
                            poll.collect { emit(it) }
                        }
                    }
                }
            }.collect { send(it) }
        }
    }

    /**
     * The resolved base URL of the currently active server, or `null` when
     * there is no server / no usable key (states where [DomainState.Content]
     * is impossible anyway).
     *
     * This is the *server-identity* key. The ViewModel uses it to drop any
     * [DomainState.Content] still tagged with the previous server's URL the
     * instant the active server changes — so server A's array / containers /
     * VMs / metrics are never shown under server B's name while B's first
     * poll is still in flight (triage #3). It tracks server identity only,
     * NOT the per-tab gate, so an ordinary tab switch for the *same* server
     * keeps showing the cached data with no skeleton flash.
     */
    val activeBaseUrl: Flow<String?> = servers.activeWithKey
        .map { active ->
            if (active == null || active.apiKey.isBlank()) null else activeUrl(active)
        }
        .distinctUntilChanged()

    /** A pending certificate-trust decision for the active server, or null. */
    data class CertPrompt(
        val serverId: String,
        val serverName: String,
        val presentedSha256: String,
        val previousSha256: String?,
    )

    // Shared across all domain streams; safe because they all target the one active server's endpoint.
    private val _certPrompt = MutableStateFlow<CertPrompt?>(null)
    val certPrompt: StateFlow<CertPrompt?> = _certPrompt.asStateFlow()

    /** Local-only trust selection delegates to the pure top-level [trustFor]. */
    private fun trustFor(active: ActiveServer, url: String): TlsTrust =
        io.github.nofuturekid.nova.data.api.trustFor(
            mode = active.mode,
            trustSelfSignedLocal = active.server.trustSelfSignedLocal,
            url = url,
            pin = active.localCertPin,
        )

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

    /**
     * Polls the global display (temperature threshold) settings.
     *
     * Display settings change rarely (only when the user edits dynamix config),
     * so a 60 s cadence (same as info) is sufficient. Used by both the Overview
     * temperature card (CPU thresholds) and the Array tab (disk thresholds).
     */
    fun displayStream(intervalMs: Long = POLL_INFO_MS): Flow<DomainState<DisplayThresholds>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetDisplayQuery()) { data -> data.toDisplayThresholds() }.withBaseUrl(baseUrl)
        }

    fun metricsStream(intervalMs: Long = POLL_METRICS_MS): Flow<DomainState<LiveMetrics>> {
        val poll = domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetMetricsQuery()) { data -> data.toLiveMetrics() }.withBaseUrl(baseUrl)
        }
        return subscriptionOrPoll(liveMetricsStream(), poll, SUB_FALLBACK_GRACE_MS)
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
     * Emitting here makes the notifications poll loop re-fetch immediately
     * instead of waiting out the (60 s) interval. A notification action
     * (archive / unread / delete / archive-all) fires it after the mutation
     * so the sheet AND the bell badge reflect the change within ~a frame.
     * A [Nudge] counter (not an event) so a nudge during an in-flight fetch
     * is not lost.
     */
    private val notificationsNudge = Nudge()

    /** Pull-to-refresh: every domain stream refetches when this fires. */
    private val refreshNudge = Nudge()

    fun notificationsStream(intervalMs: Long = POLL_NOTIFICATIONS_MS): Flow<DomainState<Notifications>> =
        domainStream(intervalMs, notificationsNudge) { client, baseUrl ->
            fetch(client, GetNotificationListQuery()) { data -> data.toNotifications() }.withBaseUrl(baseUrl)
        }

    fun pluginsStream(intervalMs: Long = POLL_PLUGINS_MS): Flow<DomainState<List<Plugin>>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetPluginsQuery()) { data -> data.toPlugins() }.withBaseUrl(baseUrl)
        }

    fun installedUnraidPluginsStream(intervalMs: Long = POLL_INSTALLED_UNRAID_PLUGINS_MS): Flow<DomainState<List<String>>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetInstalledUnraidPluginsQuery()) { data -> data.installedUnraidPlugins }.withBaseUrl(baseUrl)
        }

    fun pluginOperationsStream(intervalMs: Long = POLL_PLUGIN_OPERATIONS_MS): Flow<DomainState<List<PluginInstallOperation>>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetPluginOperationsQuery()) { data -> data.toPluginOperations() }.withBaseUrl(baseUrl)
        }

    fun networkInterfacesStream(intervalMs: Long = POLL_NETWORK_INTERFACES_MS): Flow<DomainState<List<NetworkInterface>>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetNetworkInterfacesQuery()) { data -> data.toNetworkInterfaces() }.withBaseUrl(baseUrl)
        }

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
        extraNudge: Nudge? = null,
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
                    emit(DomainState.Error(blankKeyMessage(active)))
                    return@flow
                }
                val url = activeUrl(active)
                val client = apolloFactory.build(url, active.apiKey, trustFor(active, url))
                try {
                    pollDomain(intervalMs, listOfNotNull(refreshNudge, extraNudge)) { fetch(client, url) }
                } catch (e: Throwable) {
                    val issue = e.certIssue()
                    if (issue != null) {
                        _certPrompt.value = when (issue) {
                            is CertIssue.Untrusted -> CertPrompt(active.server.id, active.server.name, issue.sha256, null)
                            is CertIssue.Changed -> CertPrompt(active.server.id, active.server.name, issue.presented, issue.pinned)
                        }
                        emit(DomainState.Error("Certificate not trusted — confirm it to connect"))
                    } else {
                        throw e
                    }
                }
            }
        }

    /**
     * Generic WebSocket subscription stream.
     *
     * Mirrors [domainStream]: keyed on `servers.activeWithKey`, emits `NoServer`
     * when no active server and `Error` when the API key is blank/undecryptable
     * (same messages via [blankKeyMessage]). For a valid server, collects
     * `apolloFactory.buildWs(url, key, trust).subscription(op).toFlow()` and
     * maps each response to `DomainState.Content(map(data), url)`.
     *
     * Cold: the WS socket opens when the flow is collected and closes when
     * collection is cancelled (gate flip / leaving Overview / app background).
     * Apollo's WS transport auto-reconnects and re-sends the connection payload
     * on drops; a sustained failure surfaces `DomainState.Error`. This bare transport has
     * no poll fallback; consumers that need one wrap it in [subscriptionOrPoll].
     */
    private fun <D : Subscription.Data, T> subscriptionStream(
        op: Subscription<D>,
        map: (D) -> T,
    ): Flow<DomainState<T>> = servers.activeWithKey
        .distinctUntilChanged()
        .flatMapLatest { active ->
            flow {
                if (active == null) { emit(DomainState.NoServer); return@flow }
                if (active.apiKey.isBlank()) {
                    emit(DomainState.Error(blankKeyMessage(active))); return@flow
                }
                val url = activeUrl(active)
                val client = apolloFactory.buildWs(url, active.apiKey, trustFor(active, url))
                try {
                    client.subscription(op).toFlow().collect { resp ->
                        val d = resp.data
                        if (d != null) {
                            emit(DomainState.Content(map(d), url))
                        } else if (resp.exception != null) {
                            emit(DomainState.Error(resp.exception!!.message ?: "Live connection error"))
                        }
                    }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emit(DomainState.Error(e.message ?: "Live connection error"))
                }
            }
        }

    /**
     * Live network throughput from the `systemMetricsNetwork` subscription,
     * with a poll fallback (ADR-0042 amendment 2026-05-30).
     *
     * Resolves the primary NIC name once (tolerate failure — [selectThroughput]
     * has a fallback for null), then runs the subscription as the PRIMARY
     * transport, degrading to the 2 s `GetNetworkThroughput` poll
     * ([networkThroughputPoll]) on sustained WS failure and returning to the
     * subscription when it recovers — so the card never goes blank on a transient
     * WS drop, the same [subscriptionOrPoll] treatment as CPU/Mem/Temp. The WS socket lives only while the flow is
     * collected (gated by the caller via [gatedStream] in the ViewModel); the
     * poll runs only while degraded.
     *
     * `metrics.network` exists upstream from Unraid API 4.35 (a list of
     * `NetworkMetrics`). An older server rejects the unknown field, failing the
     * whole operation, so support is probed ([networkSupportGated]): a supporting
     * URL is remembered for the process, an unsupporting one is re-probed on each
     * new collection (one cheap query per Overview start). An unsupporting server
     * yields no throughput, quietly (no Error).
     */
    fun networkThroughputStream(): Flow<DomainState<NetworkThroughput>> =
        servers.activeWithKey
            .distinctUntilChanged()
            .flatMapLatest { active ->
                flow {
                    if (active == null) { emit(DomainState.NoServer); return@flow }
                    if (active.apiKey.isBlank()) {
                        emit(DomainState.Error(blankKeyMessage(active))); return@flow
                    }
                    val client = buildClient(active)
                    emitAll(
                        networkSupportGated(
                            networkSupported, activeUrl(active), NETWORK_SHAPE_RETRY_MS,
                            probe = { probeShape(client) },
                            content = {
                                flow {
                                    val primary = runCatching { resolvePrimaryIface() }.getOrNull()
                                    emitAll(
                                        subscriptionOrPoll(
                                            sub = subscriptionStream(SystemMetricsNetworkSubscription()) { data ->
                                                selectThroughput(data.toIfaceSamples(), primary)
                                            },
                                            poll = networkThroughputPoll(primary),
                                            graceMs = SUB_FALLBACK_GRACE_MS,
                                        ),
                                    )
                                }
                            },
                        ),
                    )
                }
            }

    /** Server URLs known to serve `metrics.network` (see [resolveNetworkSupport]). */
    private val networkSupported: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private suspend fun probeShape(client: ApolloClient): ShapeProbe =
        try {
            val resp = client.query(GetNetworkThroughputQuery()).execute()
            classifyShapeProbe(resp.exception, resp.errors.orEmpty(), resp.data != null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ShapeProbe.Inconclusive
        }

    private fun networkThroughputPoll(
        primary: String?,
        intervalMs: Long = POLL_METRICS_MS,
    ): Flow<DomainState<NetworkThroughput>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetNetworkThroughputQuery()) { data ->
                selectThroughput(data.toIfaceSamples(), primary)
            }.withBaseUrl(baseUrl)
        }

    private suspend fun resolvePrimaryIface(): String? =
        activeClient()?.query(GetNetworkInterfacesQuery())?.execute()
            ?.data?.info?.primaryNetwork?.name

    /**
     * Live CPU+memory from the `systemMetricsCpu` + `systemMetricsMemory`
     * subscriptions, combined into the existing [LiveMetrics] model so both
     * Overview cards and [metricsStream]'s consumers stay unchanged. Each inner
     * [subscriptionStream] keys on the active server and tags Content with the
     * base URL; [combine] re-evaluates [combineMetricsStates] on every leg
     * emission, which yields Content only when BOTH legs are Content with the
     * SAME server URL (no half-populated, no cross-server card). Used as the
     * PRIMARY transport by [metricsStream], with the GetMetrics poll as the
     * [subscriptionOrPoll] fallback.
     */
    private fun liveMetricsStream(): Flow<DomainState<LiveMetrics>> {
        val cpu = subscriptionStream(SystemMetricsCpuSubscription()) { it.cpuPercentTotal() }
        val mem = subscriptionStream(SystemMetricsMemorySubscription()) { it.toMemoryTriple() }
        return combine(cpu, mem) { c, m -> combineMetricsStates(c, m) }
    }

    /**
     * Live temperature from `systemMetricsTemperature` with a poll fallback.
     *
     * Temperature is exposed by the `GetMetrics` query, so ADR-0026 mandates a
     * fallback (as for [networkThroughputStream]): the
     * subscription is primary and degrades to the 2 s `GetMetrics` poll on
     * sustained WS failure (or if no first frame arrives within the grace window),
     * returning to the subscription when it recovers — so the card never goes
     * blank on a transient WS drop. The nullable root is tolerated by
     * [toTemperature] on both paths. Gated by the caller via [gatedStream].
     */
    fun temperatureStream(): Flow<DomainState<Temperature>> =
        subscriptionOrPoll(
            sub = subscriptionStream(SystemMetricsTemperatureSubscription()) { it.toTemperature() },
            poll = metricsTemperaturePoll(),
            graceMs = SUB_FALLBACK_GRACE_MS,
        )

    private fun metricsTemperaturePoll(intervalMs: Long = POLL_METRICS_MS): Flow<DomainState<Temperature>> =
        domainStream(intervalMs) { client, baseUrl ->
            fetch(client, GetMetricsQuery()) { data -> data.toTemperature() }.withBaseUrl(baseUrl)
        }

    /**
     * Live per-container stats from the `dockerContainerStats` subscription.
     *
     * Each frame carries ONE container (id + stats). We ACCUMULATE frames into a
     * growing immutable `Map<containerId, ContainerLiveStats>` (update/insert by
     * id) and emit the growing map — the same accumulation pinned by
     * [accumulateStats]/[dockerStatsStreamForTest]. There is NO poll fallback:
     * the DockerContainer query exposes no stats fields, so on WS failure the
     * overlay simply stops growing and rows omit live stats (honest 'no live
     * stats', same stance as the network balloon). Gated to the Docker surfaces
     * by the caller (MainViewModel.dockerGate via gatedStream).
     */
    fun dockerStatsStream(): Flow<DomainState<Map<String, ContainerLiveStats>>> =
        subscriptionStream(DockerContainerStatsSubscription()) { data ->
            data.toContainerLiveStat()
        }.scan<DomainState<Pair<String, ContainerLiveStats>>, DomainState<Map<String, ContainerLiveStats>>>(
            DomainState.Loading,
        ) { acc, frame ->
            when (frame) {
                is DomainState.Content<Pair<String, ContainerLiveStats>> -> {
                    val prev = (acc as? DomainState.Content<Map<String, ContainerLiveStats>>)?.value
                        ?: emptyMap()
                    DomainState.Content(prev + frame.value, frame.serverBaseUrl)
                }
                // NoServer/Error/Loading reset the accumulation to that state — a
                // server switch or WS failure must not keep showing the old
                // server's accumulated stats. Safe covariant cast: these are all
                // DomainState<Nothing>.
                else -> @Suppress("UNCHECKED_CAST") (frame as DomainState<Map<String, ContainerLiveStats>>)
            }
        }.filter { it !is DomainState.Loading }

    /** Blank/undecryptable API key error message (ADR-0035). Shared between
     *  [domainStream] and [subscriptionStream] — single definition, no drift. */
    private fun blankKeyMessage(active: ActiveServer): String =
        // ADR-0035: a stored-but-undecryptable key is NOT the same as "no key".
        // Don't flatten it to "missing" — tell the user it can no longer be
        // decrypted so they re-enter it via the existing Add/Edit server flow.
        if (active.keyState is ApiKeyResult.Undecryptable) {
            "Saved API key can no longer be decrypted — " +
                "re-enter it for ${active.server.name}"
        } else {
            "Missing API key for ${active.server.name}"
        }

    /** Single Apollo query → DomainState wrapper. The base URL is filled in
     *  by [withBaseUrl] after the call so we keep [fetch] generic. */
    private suspend fun <D : Query.Data, T> fetch(
        client: ApolloClient,
        query: Query<D>,
        map: (D) -> T,
    ): DomainState<T> = try {
        val resp = client.query(query).execute()
        when (val c = classifyResponse(
            exception = resp.exception,
            hasErrors = resp.hasErrors(),
            errorMessage = resp.errors?.joinToString { it.message },
            dataIsNull = resp.data == null,
        )) {
            is RespClass.Cert -> throw resp.exception!!   // surface to domainStream (cause chain carries the typed cert exception)
            is RespClass.Failed -> DomainState.Error(c.message)
            RespClass.Empty -> DomainState.Error("Empty GraphQL response")
            RespClass.Ok -> DomainState.Content(map(resp.data!!))
        }
    } catch (e: ApolloException) {
        if (e.certIssue() != null) throw e   // defensive: if execute() ever throws
        DomainState.Error(e.failureMessage("Network error"))
    } catch (e: Exception) {
        if (e.certIssue() != null) throw e
        DomainState.Error(e.failureMessage("Unexpected error"))
    }

    private fun <T> DomainState<T>.withBaseUrl(baseUrl: String): DomainState<T> = when (this) {
        is DomainState.Content<T> -> DomainState.Content(value, baseUrl)
        else -> this
    }

    // ── One-shot helpers (refresh, ping, logs) ────────────────────────

    /** Pull-to-refresh: makes every active domain stream refetch right now and
     *  returns once data fetched after the request has arrived (or failed), so
     *  the spinner ends with the refreshed data on screen. Bounded by
     *  [REFRESH_MAX_WAIT_MS] for the case that no stream is collecting. */
    suspend fun refreshAll() {
        val active = servers.activeWithKey.first() ?: return
        if (active.apiKey.isBlank()) return
        val generation = refreshNudge.fire()
        withTimeoutOrNull(REFRESH_MAX_WAIT_MS) { refreshNudge.awaitCompleted(generation) }
    }

    private fun activeUrl(active: ActiveServer): String = when (active.mode) {
        ConnectionMode.Local  -> active.server.localUrl
        ConnectionMode.Remote -> active.server.remoteUrl.ifBlank { active.server.localUrl }
    }

    private fun buildClient(active: ActiveServer): ApolloClient {
        val url = activeUrl(active)
        return apolloFactory.build(url, active.apiKey, trustFor(active, url))
    }

    private fun buildLongRunningClient(active: ActiveServer): ApolloClient {
        val url = activeUrl(active)
        return apolloFactory.buildLongRunning(url, active.apiKey, trustFor(active, url))
    }

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

    suspend fun startArray() { activeClient()?.runAction(StartArrayMutation()) }
    suspend fun stopArray()  { activeClient()?.runAction(StopArrayMutation()) }

    suspend fun startContainer(id: String)   { activeClient()?.runAction(StartContainerMutation(id)) }
    suspend fun stopContainer(id: String)    { activeClient()?.runAction(StopContainerMutation(id)) }
    /** Unraid 7's docker mutations have no atomic `restart` — emulate as stop+start. */
    suspend fun restartContainer(id: String) {
        activeClient()?.let { restartContainer(it, id) }
    }
    suspend fun pauseContainer(id: String)   { activeClient()?.runAction(PauseContainerMutation(id)) }

    /** Pulls the latest image and recreates the container. Server-side work
     *  routinely takes 30 s – several minutes, so this routes through the
     *  long-running Apollo client (ADR-0016). Caller (the ViewModel) marks
     *  the container as updating before invoking and clears the mark in
     *  finally — see [MainViewModel.updateContainer]. */
    suspend fun updateContainer(id: String) {
        activeLongRunningClient()?.runAction(UpdateContainerMutation(id))
    }

    /** Updates every container that the server reports as having an update
     *  available. Same long-running plumbing as [updateContainer]; expect
     *  the HTTP connection to stay open until the *last* container in the
     *  batch has finished its pull + recreate. */
    suspend fun updateAllContainers() {
        activeLongRunningClient()?.runAction(UpdateAllContainersMutation())
    }

    suspend fun startVm(id: String)  { activeClient()?.runAction(StartVmMutation(id)) }
    suspend fun stopVm(id: String, force: Boolean = false) {
        val c = activeClient() ?: return
        if (force) c.runAction(ForceStopVmMutation(id))
        else       c.runAction(StopVmMutation(id))
    }
    suspend fun pauseVm(id: String)  { activeClient()?.runAction(PauseVmMutation(id)) }
    suspend fun resumeVm(id: String) { activeClient()?.runAction(ResumeVmMutation(id)) }
    suspend fun rebootVm(id: String) { activeClient()?.runAction(RebootVmMutation(id)) }
    suspend fun resetVm(id: String)  { activeClient()?.runAction(ResetVmMutation(id)) }

    suspend fun startParityCheck(correct: Boolean) {
        activeClient()?.runAction(StartParityCheckMutation(correct))
    }
    suspend fun pauseParityCheck()  { activeClient()?.runAction(PauseParityCheckMutation()) }
    suspend fun resumeParityCheck() { activeClient()?.runAction(ResumeParityCheckMutation()) }
    suspend fun cancelParityCheck() { activeClient()?.runAction(CancelParityCheckMutation()) }

    // ── Notification actions ──────────────────────────────────────────
    //
    // Each mutation is followed by a `recalculateOverview` (so the server's
    // unread counts are authoritative) and a nudge so the polling stream
    // re-fetches the list + overview immediately — the sheet and bell badge
    // converge on the server's truth within ~a frame (action→refetch; no
    // optimistic local mutation, which keeps the badge provably correct).

    /** Archive = mark read. Removes it from the Unread segment. */
    suspend fun archiveNotification(id: String) =
        runNotificationAction { it.runAction(ArchiveNotificationMutation(id)) }

    /** Restore an archived notification back to Unread. */
    suspend fun unreadNotification(id: String) =
        runNotificationAction { it.runAction(UnreadNotificationMutation(id)) }

    /** Permanently delete. [type] is the item's current segment. */
    suspend fun deleteNotification(id: String, type: NotifType) =
        runNotificationAction {
            val g = if (type == NotifType.Archive) GNotifType.ARCHIVE else GNotifType.UNREAD
            it.runAction(DeleteNotificationMutation(id, g))
        }

    /** Bulk archive every unread notification. */
    suspend fun archiveAllNotifications() =
        runNotificationAction { it.runAction(ArchiveAllNotificationsMutation()) }

    /** Permanently delete every archived notification. */
    suspend fun deleteArchivedNotifications() =
        runNotificationAction { it.runAction(DeleteArchivedNotificationsMutation()) }

    private suspend fun runNotificationAction(block: suspend (ApolloClient) -> Unit) =
        runNotificationActionFlow(
            resolveClient = { activeClient() },
            mutate = { c -> block(c) },
            recalculate = { c -> c.runAction(RecalculateNotificationOverviewMutation()) },
            nudge = { notificationsNudge.fire() },
        )

    /**
     * Fetches the last [tail] log lines for the given container. Returns an
     * empty list if there is no active server / key, and `null` when the
     * request FAILED — so the live tail can keep the last good logs on a
     * transient error instead of replacing them with "no logs".
     */
    suspend fun containerLogs(id: String, tail: Int = 200): List<LogLine>? {
        val client = activeClient() ?: return emptyList()
        return fetchContainerLogs(client, id, tail)
    }

    /**
     * Probe a server endpoint. Returns a structured [TestOutcome]. For the local
     * endpoint with self-signed trust enabled, pass [allowSelfSigned] = true and
     * the already-confirmed [pinnedSha256] (null on the first probe): the first
     * probe surfaces [TestOutcome.CertUntrusted] so the sheet can show the
     * fingerprint dialog; after the user confirms, a re-probe with the pin matches.
     */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        allowSelfSigned: Boolean = false,
        pinnedSha256: String? = null,
    ): TestOutcome = try {
        val trust = if (allowSelfSigned && baseUrl.startsWith("https", ignoreCase = true)) {
            TlsTrust.PinnedSelfSigned(pinnedSha256, acceptFirstUse = false)
        } else {
            TlsTrust.Default
        }
        val resp = apolloFactory.build(baseUrl, apiKey, trust).query(PingQuery()).execute()
        when (val c = classifyResponse(
            exception = resp.exception,
            hasErrors = resp.hasErrors(),
            errorMessage = resp.errors?.firstOrNull()?.message,
            dataIsNull = resp.data == null,
        )) {
            is RespClass.Cert -> when (val i = c.issue) {
                is CertIssue.Untrusted -> TestOutcome.CertUntrusted(i.sha256)
                is CertIssue.Changed -> TestOutcome.CertChanged(i.pinned, i.presented)
            }
            is RespClass.Failed -> TestOutcome.Failed(c.message)
            RespClass.Empty -> TestOutcome.Failed("No response from server")
            RespClass.Ok -> TestOutcome.Ok
        }
    } catch (e: ApolloException) {
        when (val i = e.certIssue()) {
            is CertIssue.Untrusted -> TestOutcome.CertUntrusted(i.sha256)
            is CertIssue.Changed -> TestOutcome.CertChanged(i.pinned, i.presented)
            null -> TestOutcome.Failed(e.failureMessage("Network error"))
        }
    } catch (e: Exception) {
        TestOutcome.Failed(e.message ?: "Unknown error")
    }

    /** Persist the presented cert as this server's pin and clear the prompt. The
     *  pin change restarts the polling streams (via activeWithKey). */
    suspend fun trustLocalCertificate(serverId: String, sha256: String) {
        servers.setLocalCertPin(serverId, sha256)
        _certPrompt.value = null
    }

    /**
     * Dismiss the certificate prompt without trusting. The affected server's
     * streams stay in their error state (they already ended) and do NOT restart
     * until the active server or connection mode changes again (e.g. the user
     * toggles Local/Remote or re-selects the server). This is the intended
     * "not now" behaviour — only trusting the cert (which writes a pin and thus
     * re-emits activeWithKey) resumes polling.
     */
    fun dismissCertPrompt() { _certPrompt.value = null }
}
