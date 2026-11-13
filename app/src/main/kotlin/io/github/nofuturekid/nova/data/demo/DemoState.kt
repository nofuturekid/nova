package io.github.nofuturekid.nova.data.demo

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
import io.github.nofuturekid.nova.data.model.NotifType
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.ParityCheck
import io.github.nofuturekid.nova.data.model.ParityLastRun
import io.github.nofuturekid.nova.data.model.ParityOutcome
import io.github.nofuturekid.nova.data.model.UnraidNotification
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.model.VmState

/** A parity check in progress. Progress is computed from time, so no timer has to run for it. */
data class ParityRun(val startedAtMs: Long, val baseProgress: Float, val paused: Boolean)

/**
 * Everything the user can change in the demo, as one immutable value (ADR-0050). Each action is a
 * pure function from one state to the next, so what a tap does is testable without a screen, a
 * clock or a coroutine. Nothing here is ever written to disk.
 */
data class DemoState(
    val arrayState: ArrayState = ArrayState.Started,
    val containers: List<Container> = DemoFixtures.containers.map { it.copy(webUiUrl = null) },
    val vms: List<Vm> = DemoFixtures.vms,
    val unread: List<UnraidNotification> = DemoFixtures.unreadNotes,
    val archived: List<UnraidNotification> = DemoFixtures.archivedNotes,
    val parity: ParityRun? = null,
    val parityLastRun: ParityLastRun? = ParityLastRun(ParityOutcome.Completed, errors = 0),
) {
    // ── Derived views ────────────────────────────────────────────────

    fun notifications(): Notifications = DemoFixtures.notificationsOf(unread, archived)

    /** The array as the Array tab shows it at [nowMs]: disks asleep while stopped, parity progress by time. */
    fun array(nowMs: Long): ArrayInfo {
        val s = settled(nowMs)
        val base = DemoFixtures.array
        val stopped = s.arrayState != ArrayState.Started
        return base.copy(
            state = s.arrayState,
            parity = s.parity?.let { ParityCheck(it.progressAt(nowMs), PARITY_SPEED_MBPS, it.paused) },
            disks = if (stopped) base.disks.map { it.copy(isSpinning = false, tempC = 0) } else base.disks,
            parityLastRun = if (s.parity == null) s.parityLastRun else null,
        )
    }

    /** True while a check runs (paused or not) at [nowMs]. */
    fun parityActive(nowMs: Long): Boolean = settled(nowMs).parity != null

    /** A check that reached 100 % ends as a completed run, as the server would report it. */
    fun settled(nowMs: Long): DemoState {
        val run = parity ?: return this
        return if (!run.paused && run.progressAt(nowMs) >= 1f) {
            copy(parity = null, parityLastRun = ParityLastRun(ParityOutcome.Completed, errors = 0))
        } else this
    }

    // ── Array ────────────────────────────────────────────────────────

    /** Stopping the array stops what runs on it, as Unraid does; a running check ends. */
    fun stopArray(): DemoState = copy(
        arrayState = ArrayState.Stopped,
        containers = containers.map { it.copy(status = ContainerStatus.Exited) },
        vms = vms.map { it.copy(state = VmState.Stopped) },
        parity = null,
    )

    /** Starting the array brings the containers set to start automatically back; VMs stay off. */
    fun startArray(): DemoState = copy(
        arrayState = ArrayState.Started,
        containers = containers.map { if (it.autoStart) it.copy(status = ContainerStatus.Running) else it },
    )

    fun startParity(nowMs: Long): DemoState {
        val s = settled(nowMs)
        if (s.arrayState != ArrayState.Started || s.parity != null) return s
        return s.copy(parity = ParityRun(nowMs, 0f, paused = false))
    }

    fun pauseParity(nowMs: Long): DemoState {
        val run = settled(nowMs).parity ?: return settled(nowMs)
        if (run.paused) return this
        return copy(parity = ParityRun(nowMs, run.progressAt(nowMs), paused = true))
    }

    fun resumeParity(nowMs: Long): DemoState {
        val run = parity ?: return this
        if (!run.paused) return this
        return copy(parity = ParityRun(nowMs, run.baseProgress, paused = false))
    }

    fun cancelParity(nowMs: Long): DemoState {
        val s = settled(nowMs)
        if (s.parity == null) return s
        return s.copy(parity = null, parityLastRun = ParityLastRun(ParityOutcome.Cancelled, errors = null))
    }

    // ── Containers ───────────────────────────────────────────────────

    private fun withContainer(id: String, f: (Container) -> Container) =
        copy(containers = containers.map { if (it.id == id) f(it) else it })

    fun startContainer(id: String) = withContainer(id) { it.copy(status = ContainerStatus.Running) }
    fun stopContainer(id: String) = withContainer(id) { it.copy(status = ContainerStatus.Exited) }

    /** Restart ends running, also for a container that was stopped. */
    fun restartContainer(id: String) = startContainer(id)

    fun pauseContainer(id: String) = withContainer(id) {
        if (it.status == ContainerStatus.Running) it.copy(status = ContainerStatus.Paused) else it
    }

    /** The image is replaced: the container is current and (still) running. */
    fun updateContainers(ids: Set<String>) = copy(
        containers = containers.map {
            if (it.id in ids) it.copy(updateStatus = ContainerUpdateStatus.UpToDate) else it
        },
    )

    fun idsWithUpdate(): Set<String> = containers.filter {
        it.updateStatus == ContainerUpdateStatus.UpdateAvailable || it.updateStatus == ContainerUpdateStatus.RebuildReady
    }.map { it.id }.toSet()

    // ── VMs ──────────────────────────────────────────────────────────

    private fun withVm(id: String, f: (Vm) -> Vm) = copy(vms = vms.map { if (it.id == id) f(it) else it })

    fun startVm(id: String) = withVm(id) { it.copy(state = VmState.Running) }
    fun stopVm(id: String) = withVm(id) { it.copy(state = VmState.Stopped) }
    fun pauseVm(id: String) = withVm(id) { if (it.state == VmState.Running) it.copy(state = VmState.Paused) else it }
    fun resumeVm(id: String) = withVm(id) { if (it.state == VmState.Paused) it.copy(state = VmState.Running) else it }

    /** Reboot and reset leave a running guest running (the restart itself is not shown). */
    fun rebootVm(id: String) = withVm(id) { if (it.state == VmState.Stopped) it else it.copy(state = VmState.Running) }

    // ── Notifications ────────────────────────────────────────────────

    private fun UnraidNotification.asArchived() = copy(type = NotifType.Archive)
    private fun UnraidNotification.asUnread() = copy(type = NotifType.Unread)

    fun archiveNotification(id: String): DemoState {
        val n = unread.firstOrNull { it.id == id } ?: return this
        return copy(unread = unread - n, archived = listOf(n.asArchived()) + archived)
    }

    fun unreadNotification(id: String): DemoState {
        val n = archived.firstOrNull { it.id == id } ?: return this
        return copy(archived = archived - n, unread = listOf(n.asUnread()) + unread)
    }

    fun deleteNotification(id: String, type: NotifType): DemoState = when (type) {
        NotifType.Archive -> copy(archived = archived.filterNot { it.id == id })
        NotifType.Unread -> copy(unread = unread.filterNot { it.id == id })
    }

    fun archiveAllNotifications() = copy(unread = emptyList(), archived = unread.map { it.asArchived() } + archived)
    fun deleteArchivedNotifications() = copy(archived = emptyList())

    companion object {
        /** A check takes eight minutes in the demo, long enough to watch, short enough to see it end. */
        const val PARITY_DURATION_MS = 8 * 60_000L
        const val PARITY_SPEED_MBPS = 182.0
    }
}

private fun ParityRun.progressAt(nowMs: Long): Float =
    if (paused) baseProgress
    else (baseProgress + (nowMs - startedAtMs).toFloat() / DemoState.PARITY_DURATION_MS).coerceIn(0f, 1f)
