package io.github.nofuturekid.nova.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apollographql.apollo.ApolloClient
import io.github.nofuturekid.nova.data.api.ApolloClientFactory
import io.github.nofuturekid.nova.data.alerts.AlertNotifier
import io.github.nofuturekid.nova.data.alerts.AlertScheduler
import io.github.nofuturekid.nova.data.alerts.SettingsAlertStateStore
import io.github.nofuturekid.nova.data.api.TlsTrust
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.demo.DemoState
import io.github.nofuturekid.nova.data.demo.DemoUnraid
import io.github.nofuturekid.nova.data.local.ApiKeyStore
import io.github.nofuturekid.nova.data.local.SettingsStore
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
import io.github.nofuturekid.nova.data.model.Detection
import io.github.nofuturekid.nova.data.model.NotifType
import io.github.nofuturekid.nova.data.model.ParityOutcome
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.VmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** An [ApolloClientFactory] that fails the test the moment anything asks it for a client. */
private class ForbiddenFactory : ApolloClientFactory() {
    var requests = 0
        private set

    private fun forbidden(): Nothing {
        requests++
        throw AssertionError("a network client was requested")
    }

    override fun build(baseUrl: String, apiKey: String, trust: TlsTrust): ApolloClient = forbidden()
    override fun buildLongRunning(baseUrl: String, apiKey: String, trust: TlsTrust): ApolloClient = forbidden()
    override fun buildWs(baseUrl: String, apiKey: String, trust: TlsTrust): ApolloClient = forbidden()
}

/**
 * WHY (ADR-0050): the demo server is a promise to Google's reviewers and to users: it never
 * touches the network, and it never disturbs a real server. The gateway is the one place that
 * keeps both promises, so they are tested here on the real repositories and the real stores,
 * with a factory that fails the test if it is ever asked for a client.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UnraidGatewayTest {
    private lateinit var servers: ServerRepository
    private lateinit var factory: ForbiddenFactory
    private lateinit var demo: DemoUnraid
    private lateinit var gateway: UnraidGateway
    private val scope = CoroutineScope(Dispatchers.Default)

    private val realServer = Server(
        id = "real", name = "Basement", hostname = "basement.example",
        localUrl = "http://basement.example", remoteUrl = "",
    )

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        servers = ServerRepository(
            store, ApiKeyStore(ctx),
            AlertScheduler(ctx, store, SettingsAlertStateStore(store), AlertNotifier(ctx)),
        )
        // The DataStore files outlive a test inside one JVM: start from "no server".
        store.servers.first().forEach { servers.delete(it.id) }
        factory = ForbiddenFactory()
        demo = DemoUnraid(servers, scope)
        gateway = UnraidGateway(servers, UnraidRepository(servers, factory), demo)
    }

    @After fun tearDown() { scope.cancel() }

    private suspend fun <T> firstOf(flow: Flow<DomainState<T>>): DomainState<T> = withTimeout(10_000) { flow.first() }

    private suspend fun waitFor(what: () -> Boolean) = withTimeout(10_000) { while (!what()) delay(10) }

    private suspend fun contentOf(flow: Flow<DomainState<*>>): DomainState.Content<*> {
        val s = firstOf(flow)
        assertTrue("expected content, got $s", s is DomainState.Content<*>)
        return s as DomainState.Content<*>
    }

    // WHY: every screen and every action of the demo is answered by DemoUnraid (checked through its state),
    // and no client is ever requested.
    @Test fun demoAnswersEveryScreenAndActionWithoutAClient() = runBlocking {
        servers.enterDemo()

        val streams: List<Flow<DomainState<*>>> = listOf(
            gateway.infoStream(), gateway.displayStream(), gateway.metricsStream(),
            gateway.networkThroughputStream(), gateway.temperatureStream(), gateway.arrayStream(),
            gateway.dockerStream(), gateway.dockerStatsStream(), gateway.vmsStream(),
            gateway.notificationsStream(), gateway.pluginsStream(), gateway.installedUnraidPluginsStream(),
            gateway.pluginOperationsStream(), gateway.networkInterfacesStream(),
        )
        streams.forEach { assertEquals(DemoFixtures.BASE_URL, contentOf(it).serverBaseUrl) }
        assertEquals(DemoFixtures.BASE_URL, withTimeout(10_000) { gateway.activeBaseUrl.first() })

        // Every action is checked through the demo's state: an action sent to the real repository
        // (no key, so a silent no-op) would leave the state unchanged and fail here.
        fun container(id: String) = demo.current.containers.first { it.id == id }
        fun vm(id: String) = demo.current.vms.first { it.id == id }

        gateway.stopContainer("plex"); assertEquals(ContainerStatus.Exited, container("plex").status)
        gateway.startContainer("plex", paused = false); assertEquals(ContainerStatus.Running, container("plex").status)
        // The demo restarts the container in its model whichever way the real server would be asked.
        gateway.stopContainer("plex"); gateway.restartContainer("plex", atomic = true)
        assertEquals(ContainerStatus.Running, container("plex").status)
        gateway.stopContainer("plex"); gateway.restartContainer("plex", atomic = false)
        assertEquals(ContainerStatus.Running, container("plex").status)
        gateway.pauseContainer("plex"); assertEquals(ContainerStatus.Paused, container("plex").status)
        // Resume is `unpause`, and Docker refuses `start` for a paused container: the demo's model does the same (#345).
        gateway.startContainer("plex", paused = false); assertEquals(ContainerStatus.Paused, container("plex").status)
        gateway.startContainer("plex", paused = true); assertEquals(ContainerStatus.Running, container("plex").status)
        gateway.startContainer("jellyfin", paused = false); assertEquals(ContainerStatus.Running, container("jellyfin").status)
        gateway.updateContainer("grafana")
        assertEquals(ContainerUpdateStatus.UpToDate, container("grafana").updateStatus)
        assertEquals(ContainerUpdateStatus.UpdateAvailable, container("nextcloud").updateStatus)
        gateway.updateAllContainers()
        assertEquals(ContainerUpdateStatus.UpToDate, container("nextcloud").updateStatus)

        gateway.startVm("v5"); assertEquals(VmState.Running, vm("v5").state)
        gateway.stopVm("v5", force = true); assertEquals(VmState.Stopped, vm("v5").state)
        gateway.pauseVm("v1"); assertEquals(VmState.Paused, vm("v1").state)
        gateway.resumeVm("v1"); assertEquals(VmState.Running, vm("v1").state)
        assertEquals(VmState.Paused, vm("v4").state)
        gateway.rebootVm("v4"); assertEquals(VmState.Running, vm("v4").state)
        gateway.pauseVm("v4"); assertEquals(VmState.Paused, vm("v4").state)
        gateway.resetVm("v4"); assertEquals(VmState.Running, vm("v4").state)

        gateway.startParityCheck(correct = false)
        assertNotNull(demo.current.parity); assertFalse(demo.current.parity!!.paused)
        gateway.pauseParityCheck(); assertTrue(demo.current.parity!!.paused)
        gateway.resumeParityCheck(); assertFalse(demo.current.parity!!.paused)
        gateway.cancelParityCheck()
        assertNull(demo.current.parity)
        assertEquals(ParityOutcome.Cancelled, demo.current.parityLastRun!!.outcome)

        gateway.archiveNotification("n1")
        assertTrue(demo.current.unread.none { it.id == "n1" } && demo.current.archived.any { it.id == "n1" })
        gateway.unreadNotification("n1")
        assertTrue(demo.current.unread.any { it.id == "n1" } && demo.current.archived.none { it.id == "n1" })
        gateway.deleteNotification("n2", NotifType.Unread)
        assertTrue(demo.current.unread.none { it.id == "n2" })
        gateway.archiveAllNotifications()
        assertTrue(demo.current.unread.isEmpty() && demo.current.archived.any { it.id == "n1" })
        gateway.deleteArchivedNotifications(); assertTrue(demo.current.archived.isEmpty())

        gateway.stopArray()
        assertEquals(ArrayState.Stopped, demo.current.arrayState)
        assertEquals(ContainerStatus.Exited, container("plex").status)
        gateway.startArray()
        assertEquals(ArrayState.Started, demo.current.arrayState)
        assertEquals(ContainerStatus.Running, container("plex").status)
        gateway.refreshAll()
        assertTrue(gateway.containerLogs("sonarr")!!.isNotEmpty())

        val active = servers.activeWithKey.first()!!
        assertTrue(active.server.isDemo)
        assertTrue("the demo entry has no API key", active.apiKey.isBlank())
        assertNotNull(gateway.capabilityTarget(active))
        assertEquals(Detection.Done, gateway.probeCapabilities(active).detection)

        assertEquals("a network client was requested", 0, factory.requests)
    }

    // WHY: the actions really changed the demo's memory (not a silent no-op), seen through the gateway.
    @Test fun demoActionsReachTheDemoState() = runBlocking {
        servers.enterDemo()
        gateway.stopContainer("plex")
        gateway.archiveAllNotifications()
        assertEquals(ContainerStatus.Exited, demo.current.containers.first { it.id == "plex" }.status)
        assertTrue(demo.current.unread.isEmpty())
    }

    // WHY: a real server next to the demo is unaffected. With the real server active, an action
    // goes down the real path (here: no key, so nothing happens) and must not move the demo's state.
    @Test fun aRealServerNextToTheDemoIsUnaffected() = runBlocking {
        servers.enterDemo()
        servers.upsert(realServer, apiKey = "")
        servers.setActive("real")

        val info = firstOf(gateway.infoStream())
        assertTrue("the real path answers for a real server, got $info",
            info is DomainState.Error && info.message.startsWith("Missing API key"))

        gateway.stopContainer("plex")
        gateway.archiveAllNotifications()
        assertEquals(DemoState(), demo.current)
        assertFalse(servers.activeWithKey.first()!!.server.isDemo)
        assertNull(gateway.capabilityTarget(servers.activeWithKey.first()))
        assertEquals("a network client was requested", 0, factory.requests)

        // And back: the demo is still there, with its data.
        servers.setActive(DemoFixtures.SERVER_ID)
        assertEquals(DemoFixtures.BASE_URL, contentOf(gateway.infoStream()).serverBaseUrl)
    }

    private fun assertNull(v: Any?) = org.junit.Assert.assertNull(v)

    // WHY: adding a real server while looking at the demo must leave the user on the new server,
    // not on the demo; the demo stays in the list.
    @Test fun addingARealServerWhileInTheDemoSwitchesToIt() = runBlocking {
        servers.enterDemo()
        servers.upsert(realServer, apiKey = "")
        assertEquals("real", servers.activeServer.first()!!.id)
        assertEquals(2, servers.servers.first().size)
    }

    // WHY: but a second real server does not steal the active place from the first (existing behaviour).
    @Test fun aSecondRealServerDoesNotSwitch() = runBlocking {
        servers.upsert(realServer, apiKey = "")
        servers.upsert(realServer.copy(id = "second", name = "Attic"), apiKey = "")
        assertEquals("real", servers.activeServer.first()!!.id)
    }

    // WHY: the demo entry from the list also works while a real server is active, and switches to the demo.
    @Test fun enteringTheDemoFromTheListSwitchesToIt() = runBlocking {
        servers.upsert(realServer, apiKey = "")
        servers.enterDemo()
        assertEquals(DemoFixtures.SERVER_ID, servers.activeServer.first()!!.id)
        servers.enterDemo()
        assertEquals("entering twice adds no second entry", 2, servers.servers.first().size)
    }

    // WHY: removing the demo like any server discards what was done in it, and the next server is picked.
    @Test fun removingTheDemoServerDiscardsItsState() = runBlocking {
        servers.upsert(realServer, apiKey = "")
        servers.enterDemo()
        gateway.stopContainer("plex")
        assertFalse(demo.current == DemoState())

        servers.delete(DemoFixtures.SERVER_ID)

        waitFor { demo.current == DemoState() }
        assertEquals("real", servers.activeServer.first()!!.id)
        assertTrue(servers.servers.first().none { it.isDemo })
    }
}
