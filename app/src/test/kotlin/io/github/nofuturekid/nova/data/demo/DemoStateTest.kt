package io.github.nofuturekid.nova.data.demo

import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
import io.github.nofuturekid.nova.data.model.NotifType
import io.github.nofuturekid.nova.data.model.ParityOutcome
import io.github.nofuturekid.nova.data.model.VmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHY: a reviewer who taps a control in the demo must see what the same tap does on a real
 * server: the state changes, and screens that depend on it (the update banner, the bell
 * badge, the parity bar) follow. Each test is one thing a user expects after one action.
 */
class DemoStateTest {
    private val start = DemoState()
    private fun DemoState.container(id: String) = containers.first { it.id == id }
    private fun DemoState.vm(id: String) = vms.first { it.id == id }

    // WHY: the demo never offers a link to a made-up address that the browser would try to open.
    @Test fun containersCarryNoWebUiAddress() {
        assertTrue(start.containers.all { it.webUiUrl == null })
    }

    // ── Containers ───────────────────────────────────────────────────

    @Test fun stopThenStartContainer() {
        val stopped = start.stopContainer("plex")
        assertEquals(ContainerStatus.Exited, stopped.container("plex").status)
        assertEquals(ContainerStatus.Running, stopped.startContainer("plex").container("plex").status)
    }

    // WHY: restart ends running even when the container had been stopped, as a user expects.
    @Test fun restartLeavesContainerRunning() {
        assertEquals(ContainerStatus.Running, start.stopContainer("plex").restartContainer("plex").container("plex").status)
    }

    @Test fun pauseOnlyPausesARunningContainer() {
        assertEquals(ContainerStatus.Paused, start.pauseContainer("plex").container("plex").status)
        assertEquals(ContainerStatus.Exited, start.pauseContainer("jellyfin").container("jellyfin").status)
    }

    // WHY: the update banner on the Docker tab counts containers with an update; after
    // updating them it must be gone, not just reworded.
    @Test fun updatingContainersClearsTheUpdateBanner() {
        assertEquals(setOf("nextcloud", "grafana"), start.idsWithUpdate())
        val one = start.updateContainers(setOf("nextcloud"))
        assertEquals(ContainerUpdateStatus.UpToDate, one.container("nextcloud").updateStatus)
        assertEquals(setOf("grafana"), one.idsWithUpdate())
        assertTrue(one.updateContainers(one.idsWithUpdate()).idsWithUpdate().isEmpty())
    }

    @Test fun anActionOnOneContainerLeavesTheOthersAlone() {
        val next = start.stopContainer("plex")
        assertEquals(start.containers.filter { it.id != "plex" }, next.containers.filter { it.id != "plex" })
    }

    // ── VMs ──────────────────────────────────────────────────────────

    @Test fun vmLifecycle() {
        assertEquals(VmState.Running, start.startVm("v5").vm("v5").state)
        assertEquals(VmState.Paused, start.pauseVm("v1").vm("v1").state)
        assertEquals(VmState.Running, start.resumeVm("v4").vm("v4").state)
        assertEquals(VmState.Stopped, start.stopVm("v1").vm("v1").state)
    }

    // WHY: pause means nothing for a VM that is not running; it must not turn a stopped VM into a paused one.
    @Test fun pauseAndResumeIgnoreTheWrongState() {
        assertEquals(VmState.Stopped, start.pauseVm("v5").vm("v5").state)
        assertEquals(VmState.Running, start.resumeVm("v1").vm("v1").state)
    }

    // WHY: reboot keeps a running guest running, and does not boot a stopped one.
    @Test fun rebootKeepsTheState() {
        assertEquals(VmState.Running, start.rebootVm("v1").vm("v1").state)
        assertEquals(VmState.Stopped, start.rebootVm("v5").vm("v5").state)
    }

    // ── Array ────────────────────────────────────────────────────────

    // WHY: Unraid stops Docker and the VMs with the array; a demo that kept them "running" on a stopped array would lie.
    @Test fun stoppingTheArrayStopsWhatRunsOnIt() {
        val s = start.stopArray()
        assertEquals(ArrayState.Stopped, s.array(0).state)
        assertTrue(s.containers.all { it.status == ContainerStatus.Exited })
        assertTrue(s.vms.all { it.state == VmState.Stopped })
        assertTrue(s.array(0).disks.none { it.isSpinning })
    }

    @Test fun startingTheArrayBringsAutostartContainersBackButNotVms() {
        val s = start.stopArray().startArray()
        assertEquals(ArrayState.Started, s.array(0).state)
        assertTrue(s.containers.all { it.status == ContainerStatus.Running })
        assertTrue(s.vms.all { it.state == VmState.Stopped })
    }

    // ── Parity ───────────────────────────────────────────────────────

    @Test fun parityProgressFollowsTheClock() {
        val s = start.startParity(nowMs = 1_000)
        val quarter = 1_000 + DemoState.PARITY_DURATION_MS / 4
        assertEquals(0.25f, s.array(quarter).parity!!.progress, 0.001f)
        assertNull("no last run is shown while a check runs", s.array(quarter).parityLastRun)
    }

    // WHY: pausing must freeze the bar, or "paused" would keep moving.
    @Test fun pauseFreezesAndResumeContinues() {
        val running = start.startParity(0)
        val half = DemoState.PARITY_DURATION_MS / 2
        val paused = running.pauseParity(half)
        assertTrue(paused.array(half * 2).parity!!.paused)
        assertEquals(0.5f, paused.array(half * 2).parity!!.progress, 0.001f)
        val resumed = paused.resumeParity(half * 2)
        assertFalse(resumed.array(half * 2).parity!!.paused)
        assertEquals(0.75f, resumed.array(half * 2 + DemoState.PARITY_DURATION_MS / 4).parity!!.progress, 0.001f)
    }

    @Test fun cancelEndsTheCheckAsCancelled() {
        val s = start.startParity(0).cancelParity(1_000)
        assertNull(s.array(2_000).parity)
        assertEquals(ParityOutcome.Cancelled, s.array(2_000).parityLastRun!!.outcome)
    }

    // WHY: a finished check ends as a completed run, like the server reports it, so the bar does not stay at 100 %.
    @Test fun aCheckThatReachesTheEndCompletes() {
        val s = start.startParity(0)
        val after = s.array(DemoState.PARITY_DURATION_MS + 1)
        assertNull(after.parity)
        assertEquals(ParityOutcome.Completed, after.parityLastRun!!.outcome)
        assertFalse(s.parityActive(DemoState.PARITY_DURATION_MS + 1))
    }

    @Test fun noCheckOnAStoppedArrayAndNoSecondCheck() {
        assertNull(start.stopArray().startParity(0).parity)
        val first = start.startParity(0)
        assertEquals(first.parity, first.startParity(5_000).parity)
    }

    // ── Notifications ────────────────────────────────────────────────

    // WHY: the bell badge is the unread total; archiving must lower it and the Archived tab must gain the item.
    @Test fun archivingMovesANotificationAndLowersTheBadge() {
        assertEquals(4, start.notifications().badgeCount)
        val s = start.archiveNotification("n1")
        assertEquals(3, s.notifications().badgeCount)
        assertEquals(0, s.notifications().unreadWarning)
        assertEquals(NotifType.Archive, s.notifications().archived.first().type)
        assertEquals("n1", s.notifications().archived.first().id)
    }

    @Test fun markingAsUnreadBringsItBack() {
        val s = start.archiveNotification("n1").unreadNotification("n1")
        assertEquals(4, s.notifications().badgeCount)
        assertEquals(NotifType.Unread, s.notifications().unread.first().type)
    }

    @Test fun archiveAllEmptiesUnreadAndKeepsEverything() {
        val s = start.archiveAllNotifications()
        assertEquals(0, s.notifications().badgeCount)
        assertEquals(5, s.notifications().archived.size)
        assertTrue(s.notifications().archived.all { it.type == NotifType.Archive })
    }

    @Test fun deleteRemovesFromTheNamedList() {
        assertEquals(3, start.deleteNotification("n1", NotifType.Unread).notifications().unread.size)
        assertTrue(start.deleteNotification("a1", NotifType.Archive).notifications().archived.isEmpty())
        // The wrong list names nothing to delete.
        assertEquals(4, start.deleteNotification("n1", NotifType.Archive).notifications().unread.size)
    }

    @Test fun deleteArchivedClearsOnlyTheArchive() {
        val s = start.deleteArchivedNotifications()
        assertTrue(s.notifications().archived.isEmpty())
        assertEquals(4, s.notifications().unread.size)
    }

    // WHY: the screenshots and the demo must show the same overview; counts are derived, not retyped.
    @Test fun startingNotificationsEqualTheFixture() {
        assertEquals(DemoFixtures.notifications, start.notifications())
        assertNotNull(DemoFixtures.notifications.items.firstOrNull())
    }
}
