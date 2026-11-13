package io.github.nofuturekid.nova.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.demo.DemoUnraid
import io.github.nofuturekid.nova.data.local.LayoutMode
import io.github.nofuturekid.nova.data.model.Access
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
import io.github.nofuturekid.nova.data.model.KeyAction
import io.github.nofuturekid.nova.data.model.ParityCheck
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.model.VmState
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.ui.components.ViewOnly
import io.github.nofuturekid.nova.ui.screens.array.ArrayTab
import io.github.nofuturekid.nova.ui.screens.container.ContainerDetailSheet
import io.github.nofuturekid.nova.ui.screens.docker.DockerTab
import io.github.nofuturekid.nova.ui.screens.notifications.NotificationsSheet
import io.github.nofuturekid.nova.ui.screens.vms.VmDetailSheet
import io.github.nofuturekid.nova.ui.screens.vms.VmsTab
import io.github.nofuturekid.nova.ui.theme.ThemeMode
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #315: a key that may not control something is not offered the controls, and one note says why.
 * Each surface is tested in pairs: with [Access.Denied] the buttons are gone and the note is
 * there; with [Access.Unknown] (detection failed, not finished, or a role NOVA does not know) and
 * with [Access.Allowed] the very same screen shows its buttons. Without the second half a screen
 * that hides everything would pass. Same Robolectric setup as the other tab tests.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w432dp-h768dp-night-400dpi")
class ViewOnlyControlsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) =
        rule.setContent { UnraidTheme(themeMode = ThemeMode.Dark) { content() } }

    private fun ComposeContentTestRule.hasText(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeContentTestRule.hasDescription(text: String) =
        onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    private fun container(name: String, status: ContainerStatus, update: ContainerUpdateStatus = ContainerUpdateStatus.UpToDate) =
        Container(
            id = name, name = name, image = "img/$name", status = status, autoStart = false,
            iconColorHex = null, iconUrl = null, cpu = 0.0, memMb = 0, ports = emptyList(), volumes = emptyList(),
            updateStatus = update, webUiUrl = null, networkIp = null,
        )

    private val running = container("plex", ContainerStatus.Running, ContainerUpdateStatus.UpdateAvailable)
    private val stopped = container("sonarr", ContainerStatus.Exited)
    private val paused = container("radarr", ContainerStatus.Paused)

    private val dockerNote = ViewOnly.Containers.title
    private val vmsNote = ViewOnly.Vms.title
    private val arrayNote = ViewOnly.Array.title

    // ── Docker tab ───────────────────────────────────────────────────

    private fun dockerTab(control: () -> Access, layout: () -> LayoutMode = { LayoutMode.List }) = show {
        DockerTab(
            state = DomainState.Content(listOf(running, stopped, paused)), view = layout(), query = "",
            onQueryChange = {}, onAddServer = {}, onOpenContainer = {}, onStart = {}, onRestart = {},
            onStop = {}, onUpdateAll = {}, control = control(),
        )
    }

    // WHY: #315 acceptance. A read-only key is offered no Start, Stop, Restart or Resume, in any
    // layout the user may have chosen, and the tab says why once.
    @Test fun dockerTab_deniedHidesEveryContainerButton_andShowsOneNote() {
        var layout by mutableStateOf(LayoutMode.List)
        dockerTab({ Access.Denied }, { layout })
        listOf(LayoutMode.List, LayoutMode.Grouped, LayoutMode.Compose).forEach { l ->
            layout = l
            rule.waitForIdle()
            listOf("Restart plex", "Stop plex", "Start sonarr", "Resume radarr").forEach {
                assertFalse("$it in $l", rule.hasDescription(it))
            }
            assertEquals("one note in $l", 1, rule.onAllNodesWithText(dockerNote).fetchSemanticsNodes().size)
            assertTrue(rule.hasText("Needs DOCKER:UPDATE_ANY"))
        }
    }

    // WHY: the banner keeps telling the user that updates exist (a read), but no longer offers to run them.
    @Test fun dockerTab_denied_keepsTheUpdateNotice_withoutTheAction() {
        dockerTab({ Access.Denied })
        assertTrue(rule.hasText("1 container has an update"))
        assertFalse(rule.hasText("Tap to update all"))
    }

    // WHY: fail open. Unknown access (detection failed or still running) must look exactly as before the feature.
    @Test fun dockerTab_unknownAndAllowedKeepTheButtons() {
        var access by mutableStateOf(Access.Unknown)
        dockerTab({ access })
        listOf(Access.Unknown, Access.Allowed).forEach { a ->
            access = a
            rule.waitForIdle()
            listOf("Restart plex", "Stop plex", "Start sonarr", "Resume radarr").forEach {
                assertTrue("$it with $a", rule.hasDescription(it))
            }
            assertTrue(rule.hasText("Tap to update all"))
            assertFalse(rule.hasText(dockerNote))
        }
    }

    // ── Container sheet ──────────────────────────────────────────────

    private fun containerSheet(c: Container, control: Access) = show {
        ContainerDetailSheet(
            container = c, serverBaseUrl = "", liveStats = null, isUpdating = false,
            onFetchLogs = { null }, onRefresh = {}, onDismiss = {},
            onStart = {}, onRestart = {}, onStop = {}, onUpdate = {}, control = control,
        )
    }

    @Test fun containerSheet_denied_hasNoActions_butShowsTheNote() {
        containerSheet(running, Access.Denied)
        listOf("Update container", "Restart", "Stop", "Start").forEach { assertFalse(it, rule.hasText(it)) }
        assertTrue(rule.hasText(dockerNote))
        // The container itself is still shown: only the actions are gone.
        assertTrue(rule.hasText("plex"))
    }

    @Test fun containerSheet_unknown_keepsTheActions() {
        containerSheet(running, Access.Unknown)
        listOf("Update container", "Restart", "Stop").forEach { assertTrue(it, rule.hasText(it)) }
        assertFalse(rule.hasText(dockerNote))
    }

    // ── VMs ──────────────────────────────────────────────────────────

    private val vmRunning = Vm("v1", "Windows 11", VmState.Running, 4, 8, null)
    private val vmPaused = Vm("v2", "Debian", VmState.Paused, 2, 4, null)
    private val vmStopped = Vm("v3", "macOS", VmState.Stopped, 2, 4, null)

    private fun vmsTab(control: Access) = show {
        VmsTab(
            state = DomainState.Content(listOf(vmRunning, vmPaused, vmStopped)), view = LayoutMode.List,
            onAddServer = {}, onStart = {}, onResume = {}, onPause = {}, onOpenVm = {}, control = control,
        )
    }

    @Test fun vmsTab_deniedHidesPauseResumeStart_andShowsOneNote() {
        vmsTab(Access.Denied)
        listOf("Pause Windows 11", "Resume Debian", "Start macOS").forEach { assertFalse(it, rule.hasDescription(it)) }
        assertEquals(1, rule.onAllNodesWithText(vmsNote).fetchSemanticsNodes().size)
        assertTrue(rule.hasText("Needs VMS:UPDATE_ANY"))
        // The VMs themselves stay listed.
        assertTrue(rule.hasText("Windows 11"))
    }

    @Test fun vmsTab_unknownKeepsTheButtons() {
        vmsTab(Access.Unknown)
        listOf("Pause Windows 11", "Resume Debian", "Start macOS").forEach { assertTrue(it, rule.hasDescription(it)) }
        assertFalse(rule.hasText(vmsNote))
    }

    private fun vmSheet(vm: Vm, control: Access) = show {
        VmDetailSheet(
            vm = vm, onDismiss = {}, onStart = {}, onResume = {}, onPause = {}, onStop = {},
            onForceStop = {}, onReboot = {}, onReset = {}, control = control,
        )
    }

    // WHY: the VM sheet has the most buttons (Reboot, Reset and Force stop are the dangerous ones);
    // a read-only key must see none of them.
    @Test fun vmSheet_denied_hidesEveryAction() {
        vmSheet(vmRunning, Access.Denied)
        listOf("Pause", "Stop", "Reboot", "Reset", "Force stop").forEach { assertFalse(it, rule.hasText(it)) }
        assertTrue(rule.hasText(vmsNote))
        assertTrue(rule.hasText("Windows 11"))
    }

    @Test fun vmSheet_unknown_keepsEveryAction() {
        vmSheet(vmRunning, Access.Unknown)
        listOf("Pause", "Stop", "Reboot", "Reset", "Force stop").forEach { assertTrue(it, rule.hasText(it)) }
        assertFalse(rule.hasText(vmsNote))
    }

    @Test fun vmSheet_deniedWhileStopped_hidesStart() {
        vmSheet(vmStopped, Access.Denied)
        assertFalse(rule.hasText("Start"))
        assertTrue(rule.hasText(vmsNote))
    }

    // ── Array ────────────────────────────────────────────────────────

    private fun arrayInfo(state: ArrayState, parity: ParityCheck? = null) =
        DemoFixtures.array.copy(state = state, parity = parity)

    private fun arrayTab(info: ArrayInfo, control: Access) = show {
        ArrayTab(
            state = DomainState.Content(info), view = LayoutMode.List, onAddServer = {},
            onStartArray = {}, onStopArray = {}, onStartParity = {}, onPauseParity = {},
            onResumeParity = {}, onCancelParity = {}, control = control,
        )
    }

    // WHY: array start/stop and parity checks are the most consequential controls; a view-only key sees none.
    @Test fun arrayTab_denied_hidesStopAndParityCheck() {
        arrayTab(arrayInfo(ArrayState.Started), Access.Denied)
        listOf("Stop", "Start", "Check parity").forEach { assertFalse(it, rule.hasText(it)) }
        assertTrue(rule.hasText(arrayNote))
        assertTrue(rule.hasText("Needs ARRAY:UPDATE_ANY"))
        // The disks stay listed.
        assertTrue(rule.hasText("disk1"))
    }

    @Test fun arrayTab_denied_hidesStartWhenStopped() {
        arrayTab(arrayInfo(ArrayState.Stopped), Access.Denied)
        assertFalse(rule.hasText("Start"))
        assertTrue(rule.hasText(arrayNote))
    }

    @Test fun arrayTab_denied_hidesPauseAndCancelOfARunningCheck_butKeepsItsProgress() {
        arrayTab(arrayInfo(ArrayState.Parity, ParityCheck(0.4f, 120.0, paused = false)), Access.Denied)
        listOf("Pause", "Cancel", "Resume").forEach { assertFalse(it, rule.hasText(it)) }
        assertTrue(rule.hasText("Parity operation in progress"))
        assertTrue(rule.hasText("40%"))
    }

    @Test fun arrayTab_unknown_keepsEveryControl() {
        arrayTab(arrayInfo(ArrayState.Started), Access.Unknown)
        listOf("Stop", "Check parity").forEach { assertTrue(it, rule.hasText(it)) }
        assertFalse(rule.hasText(arrayNote))
    }

    @Test fun arrayTab_unknown_keepsPauseAndCancel() {
        arrayTab(arrayInfo(ArrayState.Parity, ParityCheck(0.4f, 120.0, paused = false)), Access.Unknown)
        listOf("Pause", "Cancel").forEach { assertTrue(it, rule.hasText(it)) }
    }

    // ── Notifications ────────────────────────────────────────────────

    private fun notificationsSheet(update: Access, delete: Access) = show {
        NotificationsSheet(
            state = DomainState.Content(DemoFixtures.notifications),
            onDismiss = {}, onArchive = {}, onUnread = {}, onArchiveAll = {},
            onDeleteAllArchivedRequest = {}, onDeleteRequest = {}, update = update, delete = delete,
        )
    }

    // WHY: the two permissions are separate on the server (UPDATE_ANY archives, DELETE_ANY deletes),
    // so a key that may archive but not delete keeps exactly the archive buttons.
    @Test fun notifications_updateAllowedDeleteDenied_hidesOnlyDelete() {
        notificationsSheet(Access.Allowed, Access.Denied)
        assertTrue(rule.hasText("Archive all"))
        assertTrue(rule.hasDescription("Archive (mark read)"))
        assertFalse(rule.hasDescription("Delete notification"))
        assertTrue(rule.hasText("This API key can't delete notifications."))
        assertTrue(rule.hasText("Needs NOTIFICATIONS:DELETE_ANY"))
        // On the archived tab "Delete all archived" is a delete too.
        rule.onNodeWithText("Archived (1)").performClick()
        rule.waitForIdle()
        assertFalse(rule.hasText("Delete all archived"))
        assertTrue(rule.hasDescription("Unarchive (restore to unread)"))
    }

    @Test fun notifications_bothDenied_hidesEveryActionAndShowsOneNote() {
        notificationsSheet(Access.Denied, Access.Denied)
        assertFalse(rule.hasText("Archive all"))
        assertFalse(rule.hasDescription("Archive (mark read)"))
        assertFalse(rule.hasDescription("Delete notification"))
        assertEquals(1, rule.onAllNodesWithText("View only. This API key can't archive or delete notifications.").fetchSemanticsNodes().size)
        assertTrue(rule.hasText("Needs NOTIFICATIONS:UPDATE_ANY and NOTIFICATIONS:DELETE_ANY"))
        // The notifications themselves are still readable.
        assertTrue(rule.hasText("Warning [TOWER] - Disk 3 temperature"))
    }

    @Test fun notifications_updateDeniedDeleteAllowed_hidesOnlyArchive() {
        notificationsSheet(Access.Denied, Access.Allowed)
        assertFalse(rule.hasText("Archive all"))
        assertFalse(rule.hasDescription("Archive (mark read)"))
        assertTrue(rule.hasDescription("Delete notification"))
        assertTrue(rule.hasText("This API key can't archive notifications."))
    }

    @Test fun notifications_unknownKeepsEveryButton() {
        notificationsSheet(Access.Unknown, Access.Unknown)
        assertTrue(rule.hasText("Archive all"))
        assertTrue(rule.hasDescription("Archive (mark read)"))
        assertTrue(rule.hasDescription("Delete notification"))
        assertFalse(rule.hasText("View only. This API key can't archive or delete notifications."))
    }

    // ── The demo ─────────────────────────────────────────────────────

    // WHY: the demo has no key and no server to refuse anything; it must look like an admin server.
    // The access values come from what the demo reports to the capability model, exactly as MainScreen
    // derives them, and no screen may show a note or lose a button.
    @Test fun demo_hidesNoControl() {
        val caps = DemoUnraid(TestScope(), MutableStateFlow(true)) { 0L }.capabilities().toCapabilities(null)
        KeyAction.entries.forEach { assertEquals(it.name, Access.Allowed, caps.access(it)) }
        assertNull(ViewOnly.notifications(caps.access(KeyAction.UpdateNotifications), caps.access(KeyAction.DeleteNotifications)))

        show {
            DockerTab(
                state = DomainState.Content(DemoFixtures.containers), view = LayoutMode.List, query = "",
                onQueryChange = {}, onAddServer = {}, onOpenContainer = {}, onStart = {}, onRestart = {},
                onStop = {}, onUpdateAll = {}, control = caps.access(KeyAction.ControlDocker),
            )
        }
        assertTrue(rule.hasDescription("Restart plex"))
        assertTrue(rule.hasDescription("Start jellyfin"))
        assertFalse(rule.hasText(dockerNote))
    }

    @Test fun demo_vmsKeepTheirControls() {
        val caps = DemoUnraid(TestScope(), MutableStateFlow(true)) { 0L }.capabilities().toCapabilities(null)
        show {
            VmsTab(
                state = DomainState.Content(DemoFixtures.vms), view = LayoutMode.List, onAddServer = {},
                onStart = {}, onResume = {}, onPause = {}, onOpenVm = {},
                control = caps.access(KeyAction.ControlVms),
            )
        }
        assertTrue(rule.hasDescription("Pause Windows 11"))
        assertFalse(rule.hasText(vmsNote))
    }

    // ── The note's wording ───────────────────────────────────────────

    // WHY: the permission named in the note is the one the button is hidden by; they must not drift.
    @Test fun noteTexts_nameThePermissionOfTheirKeyAction() {
        assertEquals("Needs ${KeyAction.ControlDocker.permission}", ViewOnly.Containers.needs)
        assertEquals("Needs ${KeyAction.ControlVms.permission}", ViewOnly.Vms.needs)
        assertEquals("Needs ${KeyAction.ControlArray.permission}", ViewOnly.Array.needs)
        assertEquals("Needs DOCKER:UPDATE_ANY", ViewOnly.Containers.needs)
        assertNotNull(ViewOnly.notifications(Access.Denied, Access.Unknown))
        assertNull(ViewOnly.notifications(Access.Unknown, Access.Unknown))
        assertNull(ViewOnly.notifications(Access.Allowed, Access.Allowed))
    }
}
