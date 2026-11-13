package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.demo.DemoUnraid
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.CapabilityTarget
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.DisplayThresholds
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that decides where a screen's data comes from (ADR-0050): the GraphQL
 * [UnraidRepository] for a real server, [DemoUnraid] for the demo entry. The screens' view
 * models talk to this class and use the same names as before.
 *
 * The decision is one question, "is the active server the demo entry?", asked here and nowhere
 * else. For a real server every call is handed to [UnraidRepository] unchanged, so a real
 * server behaves exactly as before; the demo entry never reaches [UnraidRepository], so it
 * never reaches an Apollo client, a key or a socket.
 */
@Singleton
class UnraidGateway @Inject constructor(
    private val servers: ServerRepository,
    private val real: UnraidRepository,
    private val demo: DemoUnraid,
) {
    private val demoActive: Flow<Boolean> =
        servers.activeServer.map { it?.isDemo == true }.distinctUntilChanged()

    private suspend fun isDemo(): Boolean = servers.activeServer.first()?.isDemo == true

    private fun <T> routed(onReal: () -> Flow<T>, onDemo: () -> Flow<T>): Flow<T> =
        demoActive.flatMapLatest { if (it) onDemo() else onReal() }

    private suspend fun action(onReal: suspend () -> Unit, onDemo: suspend () -> Unit) =
        if (isDemo()) onDemo() else onReal()

    // ── Streams ──────────────────────────────────────────────────────

    fun infoStream(): Flow<DomainState<ServerInfo>> = routed(real::infoStream, demo::infoStream)
    fun displayStream(): Flow<DomainState<DisplayThresholds>> = routed(real::displayStream, demo::displayStream)
    fun metricsStream(): Flow<DomainState<LiveMetrics>> = routed(real::metricsStream, demo::metricsStream)
    fun networkThroughputStream(): Flow<DomainState<NetworkThroughput>> =
        routed(real::networkThroughputStream, demo::networkThroughputStream)
    fun temperatureStream(): Flow<DomainState<Temperature>> = routed(real::temperatureStream, demo::temperatureStream)
    fun arrayStream(): Flow<DomainState<ArrayInfo>> = routed(real::arrayStream, demo::arrayStream)
    fun dockerStream(): Flow<DomainState<List<Container>>> = routed(real::dockerStream, demo::dockerStream)
    fun dockerStatsStream(): Flow<DomainState<Map<String, ContainerLiveStats>>> =
        routed(real::dockerStatsStream, demo::dockerStatsStream)
    fun vmsStream(): Flow<DomainState<List<Vm>>> = routed(real::vmsStream, demo::vmsStream)
    fun notificationsStream(): Flow<DomainState<Notifications>> =
        routed(real::notificationsStream, demo::notificationsStream)
    fun pluginsStream(): Flow<DomainState<List<Plugin>>> = routed(real::pluginsStream, demo::pluginsStream)
    fun installedUnraidPluginsStream(): Flow<DomainState<List<String>>> =
        routed(real::installedUnraidPluginsStream, demo::installedUnraidPluginsStream)
    fun pluginOperationsStream(): Flow<DomainState<List<PluginInstallOperation>>> =
        routed(real::pluginOperationsStream, demo::pluginOperationsStream)
    fun networkInterfacesStream(): Flow<DomainState<List<NetworkInterface>>> =
        routed(real::networkInterfacesStream, demo::networkInterfacesStream)

    /** The key that says which server the data belongs to; the demo has its own, which no URL can equal. */
    val activeBaseUrl: Flow<String?> = combine(demoActive, real.activeBaseUrl) { isDemo, url ->
        if (isDemo) DemoFixtures.BASE_URL else url
    }.distinctUntilChanged()

    // ── One-shot calls ───────────────────────────────────────────────

    suspend fun refreshAll() = action(onReal = { real.refreshAll() }, onDemo = { demo.refresh() })

    suspend fun containerLogs(id: String, tail: Int = 200): List<LogLine>? =
        if (isDemo()) demo.containerLogs(id, tail) else real.containerLogs(id, tail)

    /** The address and key capabilities are detected for; for the demo a fixed target without a key. */
    fun capabilityTarget(active: ActiveServer?): CapabilityTarget? =
        if (active?.server?.isDemo == true) CapabilityTarget(active.server.id, DemoFixtures.BASE_URL, "")
        else real.capabilityTarget(active)

    /** Detection for [active]; the demo answers from memory and never builds a client. */
    suspend fun probeCapabilities(active: ActiveServer): ProbeOutcome =
        if (active.server.isDemo) demo.capabilities() else real.probeCapabilities(active)

    // ── Actions ──────────────────────────────────────────────────────

    suspend fun startArray() = action({ real.startArray() }, { demo.startArray() })
    suspend fun stopArray() = action({ real.stopArray() }, { demo.stopArray() })

    /** [paused] picks `unpause` over `start` (#345); the demo resumes or starts the container in its model. */
    suspend fun startContainer(id: String, paused: Boolean) =
        action({ real.startContainer(id, paused) }, { demo.startContainer(id, paused) })
    suspend fun stopContainer(id: String) = action({ real.stopContainer(id) }, { demo.stopContainer(id) })
    /** [atomic] is the real server's choice (#346); the demo restarts the container in its model either way. */
    suspend fun restartContainer(id: String, atomic: Boolean) =
        action({ real.restartContainer(id, atomic) }, { demo.restartContainer(id) })
    suspend fun pauseContainer(id: String) = action({ real.pauseContainer(id) }, { demo.pauseContainer(id) })
    suspend fun updateContainer(id: String) = action({ real.updateContainer(id) }, { demo.updateContainer(id) })
    suspend fun updateAllContainers() = action({ real.updateAllContainers() }, { demo.updateAllContainers() })

    suspend fun startVm(id: String) = action({ real.startVm(id) }, { demo.startVm(id) })
    suspend fun stopVm(id: String, force: Boolean = false) =
        action({ real.stopVm(id, force) }, { demo.stopVm(id) })
    suspend fun pauseVm(id: String) = action({ real.pauseVm(id) }, { demo.pauseVm(id) })
    suspend fun resumeVm(id: String) = action({ real.resumeVm(id) }, { demo.resumeVm(id) })
    suspend fun rebootVm(id: String) = action({ real.rebootVm(id) }, { demo.rebootVm(id) })
    suspend fun resetVm(id: String) = action({ real.resetVm(id) }, { demo.rebootVm(id) })

    suspend fun startParityCheck(correct: Boolean) =
        action({ real.startParityCheck(correct) }, { demo.startParityCheck() })
    suspend fun pauseParityCheck() = action({ real.pauseParityCheck() }, { demo.pauseParityCheck() })
    suspend fun resumeParityCheck() = action({ real.resumeParityCheck() }, { demo.resumeParityCheck() })
    suspend fun cancelParityCheck() = action({ real.cancelParityCheck() }, { demo.cancelParityCheck() })

    suspend fun archiveNotification(id: String) =
        action({ real.archiveNotification(id) }, { demo.archiveNotification(id) })
    suspend fun unreadNotification(id: String) =
        action({ real.unreadNotification(id) }, { demo.unreadNotification(id) })
    suspend fun deleteNotification(id: String, type: NotifType) =
        action({ real.deleteNotification(id, type) }, { demo.deleteNotification(id, type) })
    suspend fun archiveAllNotifications() =
        action({ real.archiveAllNotifications() }, { demo.archiveAllNotifications() })
    suspend fun deleteArchivedNotifications() =
        action({ real.deleteArchivedNotifications() }, { demo.deleteArchivedNotifications() })

    // ── Real servers only (certificate trust belongs to a real connection) ──

    val certPrompt: StateFlow<UnraidRepository.CertPrompt?> get() = real.certPrompt
    suspend fun trustLocalCertificate(serverId: String, sha256: String) = real.trustLocalCertificate(serverId, sha256)
    fun dismissCertPrompt() = real.dismissCertPrompt()
}
