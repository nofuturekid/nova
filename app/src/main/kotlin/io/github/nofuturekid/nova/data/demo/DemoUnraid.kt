package io.github.nofuturekid.nova.data.demo

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ApiVersion
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.Detection
import io.github.nofuturekid.nova.data.model.DisplayThresholds
import io.github.nofuturekid.nova.data.model.KeyAccess
import io.github.nofuturekid.nova.data.model.LiveMetrics
import io.github.nofuturekid.nova.data.model.LogLine
import io.github.nofuturekid.nova.data.model.NetworkInterface
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.NotifType
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Plugin
import io.github.nofuturekid.nova.data.model.PluginInstallOperation
import io.github.nofuturekid.nova.data.model.ServerInfo
import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.ProbeOutcome
import io.github.nofuturekid.nova.data.repository.ServerRepository
import io.github.nofuturekid.nova.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The demo server (ADR-0050): the same streams and actions as `UnraidRepository`, answered from an
 * in-memory [DemoState]. It has no Apollo client, no OkHttp, no URL and no key: the class cannot
 * open a connection, whatever a caller asks of it.
 *
 * Life cycle. The state lives in the process only. It is replaced by a fresh one the moment the
 * demo entry is no longer in the server list (removing the demo server), and it dies with the
 * process. No stream owns a timer: the live-looking figures come from cold flows that tick only
 * while a screen collects them, so nothing runs on after the screen is gone.
 */
@Singleton
class DemoUnraid internal constructor(
    scope: CoroutineScope,
    demoEntryPresent: Flow<Boolean>,
    private val clock: () -> Long,
) {
    @Inject
    constructor(servers: ServerRepository, @ApplicationScope scope: CoroutineScope) : this(
        scope = scope,
        demoEntryPresent = servers.servers.map { list -> list.any { it.isDemo } },
        clock = System::currentTimeMillis,
    )

    private val state = MutableStateFlow(DemoState())
    private var startedAtMs = clock()

    init {
        scope.launch {
            demoEntryPresent.distinctUntilChanged().collect { present ->
                if (!present) reset()
            }
        }
    }

    /** Back to the starting state, as after a fresh start of the app. */
    internal fun reset() {
        state.value = DemoState()
        startedAtMs = clock()
    }

    internal val current: DemoState get() = state.value

    private fun tick(): Long = (clock() - startedAtMs).coerceAtLeast(0L) / DemoMetrics.TICK_MS

    private fun <T> content(value: T): DomainState<T> = DomainState.Content(value, DemoFixtures.BASE_URL)

    /** A value per tick for as long as somebody collects: the first at once, then on every tick boundary. */
    private fun <T> ticking(produce: (tick: Long) -> T): Flow<DomainState<T>> = flow {
        while (true) {
            emit(content(produce(tick())))
            delay(DemoMetrics.TICK_MS - (clock() - startedAtMs).coerceAtLeast(0L) % DemoMetrics.TICK_MS)
        }
    }

    // ── Streams ──────────────────────────────────────────────────────

    fun infoStream(): Flow<DomainState<ServerInfo>> = flowOf(content(DemoFixtures.info))
    fun displayStream(): Flow<DomainState<DisplayThresholds>> = flowOf(content(DemoFixtures.thresholds))
    fun metricsStream(): Flow<DomainState<LiveMetrics>> = ticking(DemoMetrics::live)
    fun networkThroughputStream(): Flow<DomainState<NetworkThroughput>> = ticking(DemoMetrics::network)
    fun temperatureStream(): Flow<DomainState<Temperature>> = ticking(DemoMetrics::temperature)

    /** While a parity check runs the progress moves, so the stream ticks; otherwise it only follows the state. */
    fun arrayStream(): Flow<DomainState<ArrayInfo>> = state.flatMapLatest { s ->
        if (s.parityActive(clock())) {
            flow {
                while (true) {
                    val now = clock()
                    emit(content(s.array(now)))
                    if (!s.parityActive(now)) break
                    delay(ARRAY_TICK_MS)
                }
            }
        } else {
            flowOf(content(s.array(clock())))
        }
    }

    fun dockerStream(): Flow<DomainState<List<Container>>> = state.map { content(it.containers) }

    fun dockerStatsStream(): Flow<DomainState<Map<String, ContainerLiveStats>>> = state.flatMapLatest { s ->
        ticking { n ->
            s.containers.filter { it.status == ContainerStatus.Running }.mapNotNull { c ->
                DemoFixtures.stats[c.id]?.let { base -> c.id to DemoMetrics.containerStats(base, n, c.id.length) }
            }.toMap()
        }
    }

    fun vmsStream(): Flow<DomainState<List<Vm>>> = state.map { content(it.vms) }
    fun notificationsStream(): Flow<DomainState<Notifications>> = state.map { content(it.notifications()) }
    fun pluginsStream(): Flow<DomainState<List<Plugin>>> = flowOf(content(DemoFixtures.plugins))
    fun installedUnraidPluginsStream(): Flow<DomainState<List<String>>> = flowOf(content(DemoFixtures.installedPlugins))
    fun pluginOperationsStream(): Flow<DomainState<List<PluginInstallOperation>>> = flowOf(content(DemoFixtures.pluginOperations))
    fun networkInterfacesStream(): Flow<DomainState<List<NetworkInterface>>> = flowOf(content(DemoFixtures.networkInterfaces))

    /** The last [tail] lines of a running container's log, with the newest time at the moment of the call. */
    fun containerLogs(id: String, tail: Int): List<LogLine> {
        val c = state.value.containers.firstOrNull { it.id == id } ?: return emptyList()
        return if (c.status == ContainerStatus.Running) DemoFixtures.logLines(c.name, clock(), tail) else emptyList()
    }

    /** What the capability detection reports for the demo: every control allowed, a known API version. */
    fun capabilities(): ProbeOutcome = ProbeOutcome(
        detection = Detection.Done,
        unraidVersion = DemoFixtures.info.unraidVersion,
        apiVersionText = DEMO_API_VERSION,
        apiVersion = ApiVersion.parse(DEMO_API_VERSION),
        access = KeyAccess.Known(roles = setOf("ADMIN"), grants = emptySet()),
    )

    // ── Actions ──────────────────────────────────────────────────────

    private fun act(f: (DemoState) -> DemoState) = state.update(f)

    suspend fun refresh() = delay(REFRESH_MS)

    fun startArray() = act { it.startArray() }
    fun stopArray() = act { it.stopArray() }

    fun startContainer(id: String, paused: Boolean) =
        act { if (paused) it.unpauseContainer(id) else it.startContainer(id) }
    fun stopContainer(id: String) = act { it.stopContainer(id) }
    fun restartContainer(id: String) = act { it.restartContainer(id) }
    fun pauseContainer(id: String) = act { it.pauseContainer(id) }

    /** An update "pulls" for a moment, like the real one, then the container is current. */
    suspend fun updateContainer(id: String) {
        delay(UPDATE_MS)
        act { it.updateContainers(setOf(id)) }
    }

    suspend fun updateAllContainers() {
        val ids = state.value.idsWithUpdate()
        delay(UPDATE_MS)
        act { it.updateContainers(ids) }
    }

    fun startVm(id: String) = act { it.startVm(id) }
    fun stopVm(id: String) = act { it.stopVm(id) }
    fun pauseVm(id: String) = act { it.pauseVm(id) }
    fun resumeVm(id: String) = act { it.resumeVm(id) }
    fun rebootVm(id: String) = act { it.rebootVm(id) }

    fun startParityCheck() = act { it.startParity(clock()) }
    fun pauseParityCheck() = act { it.pauseParity(clock()) }
    fun resumeParityCheck() = act { it.resumeParity(clock()) }
    fun cancelParityCheck() = act { it.cancelParity(clock()) }

    fun archiveNotification(id: String) = act { it.archiveNotification(id) }
    fun unreadNotification(id: String) = act { it.unreadNotification(id) }
    fun deleteNotification(id: String, type: NotifType) = act { it.deleteNotification(id, type) }
    fun archiveAllNotifications() = act { it.archiveAllNotifications() }
    fun deleteArchivedNotifications() = act { it.deleteArchivedNotifications() }

    companion object {
        const val DEMO_API_VERSION = "4.37.11"
        private const val ARRAY_TICK_MS = 2_000L
        private const val REFRESH_MS = 300L
        private const val UPDATE_MS = 1_500L
    }
}
