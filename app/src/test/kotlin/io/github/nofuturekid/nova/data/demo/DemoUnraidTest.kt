package io.github.nofuturekid.nova.data.demo

import io.github.nofuturekid.nova.data.model.Access
import io.github.nofuturekid.nova.data.model.ApiFeature
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
import io.github.nofuturekid.nova.data.model.KeyAction
import io.github.nofuturekid.nova.data.model.Support
import io.github.nofuturekid.nova.data.repository.DomainState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoUnraidTest {
    private fun TestScope.demo(entry: MutableStateFlow<Boolean> = MutableStateFlow(true)) =
        DemoUnraid(
            scope = backgroundScope,
            demoEntryPresent = entry,
            clock = { currentTime },
        )

    // WHY: the demo ends with its server entry. A reviewer who removes it and adds it again
    // must meet the starting state, not the notifications they archived before.
    @Test fun removingTheDemoEntryDiscardsTheState() = runTest(UnconfinedTestDispatcher()) {
        val entry = MutableStateFlow(true)
        val demo = demo(entry)
        demo.stopContainer("plex")
        demo.archiveAllNotifications()
        assertEquals(ContainerStatus.Exited, demo.current.containers.first { it.id == "plex" }.status)

        entry.value = false

        assertEquals(DemoState(), demo.current)
    }

    // WHY: while the entry is there, state must survive (the list flow re-emitting is not a removal).
    @Test fun theStateSurvivesWhileTheEntryExists() = runTest(UnconfinedTestDispatcher()) {
        val entry = MutableStateFlow(true)
        val demo = demo(entry)
        demo.stopContainer("plex")
        entry.value = true
        assertEquals(ContainerStatus.Exited, demo.current.containers.first { it.id == "plex" }.status)
    }

    // WHY: a screen sees an action at once through its stream, without any refetch.
    @Test fun streamsFollowActions() = runTest(UnconfinedTestDispatcher()) {
        val demo = demo()
        val seen = mutableListOf<Int>()
        backgroundScope.launch {
            demo.notificationsStream().collect { seen += (it as DomainState.Content).value.badgeCount }
        }
        demo.archiveNotification("n1")
        demo.archiveAllNotifications()
        assertEquals(listOf(4, 3, 0), seen)
    }

    // WHY: the update runs "for a moment" (the pill is visible), then the banner goes.
    @Test fun anUpdateTakesAMomentThenSucceeds() = runTest(UnconfinedTestDispatcher()) {
        val demo = demo()
        val job = backgroundScope.launch { demo.updateContainer("nextcloud") }
        assertEquals(
            ContainerUpdateStatus.UpdateAvailable,
            demo.current.containers.first { it.id == "nextcloud" }.updateStatus,
        )
        advanceTimeBy(2_000)
        job.join()
        assertEquals(
            ContainerUpdateStatus.UpToDate,
            demo.current.containers.first { it.id == "nextcloud" }.updateStatus,
        )
        assertTrue(demo.current.idsWithUpdate() == setOf("grafana"))
    }

    // WHY: "Update all" is the one that clears the banner on the Docker tab.
    @Test fun updateAllClearsEveryUpdate() = runTest(UnconfinedTestDispatcher()) {
        val demo = demo()
        demo.updateAllContainers()
        assertTrue(demo.current.idsWithUpdate().isEmpty())
    }

    // WHY: no timer may outlive a screen. A stream that is collected three times and
    // then dropped must leave nothing scheduled behind it.
    @Test fun liveFiguresLeaveNoTimerBehind() = runTest {
        val demo = demo()
        val three = demo.metricsStream().take(3).toList()
        assertEquals(3, three.size)
        // With a ticker left running this would never return (virtual time has no end).
        testScheduler.advanceUntilIdle()
    }

    // WHY: deterministic figures. The same moment gives the same value, so a screenshot or a test can rely on it.
    @Test fun liveFiguresAreAPureFunctionOfTheTick() = runTest {
        val first = (demo().metricsStream().first() as DomainState.Content).value
        val again = (demo().metricsStream().first() as DomainState.Content).value
        assertEquals(DemoMetrics.live(0), first)
        assertEquals(first, again)
        val ticks = demo().metricsStream().take(3).toList().map { (it as DomainState.Content).value }
        assertEquals(listOf(DemoMetrics.live(0), DemoMetrics.live(1), DemoMetrics.live(2)), ticks)
    }

    @Test fun statsOnlyForRunningContainers() = runTest(UnconfinedTestDispatcher()) {
        val demo = demo()
        demo.stopContainer("plex")
        val stats = (demo.dockerStatsStream().first() as DomainState.Content).value
        assertTrue("plex" !in stats)
        assertTrue("sonarr" in stats)
    }

    @Test fun logsOnlyForRunningContainers() = runTest(UnconfinedTestDispatcher()) {
        val demo = demo()
        assertEquals(20, demo.containerLogs("plex", 20).size)
        assertTrue(demo.containerLogs("jellyfin", 20).isEmpty())
        assertTrue(demo.containerLogs("no-such-container", 20).isEmpty())
    }

    // WHY: later permission-based hiding (#315) must not hide demo controls: everything allowed, a known version.
    @Test fun capabilitiesAllowEveryControl() = runTest {
        val caps = demo().capabilities().toCapabilities(null)
        KeyAction.entries.forEach { assertEquals(it.name, Access.Allowed, caps.access(it)) }
        ApiFeature.entries.forEach { assertEquals(it.name, Support.Yes, caps.supports(it)) }
        assertTrue(!caps.apiTooOld)
    }
}
