package io.github.nofuturekid.nova.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.data.local.LayoutMode
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.Disk
import io.github.nofuturekid.nova.data.model.DiskStatus
import io.github.nofuturekid.nova.data.model.DiskType
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.ErrorReason
import io.github.nofuturekid.nova.ui.screens.array.ArrayTab
import io.github.nofuturekid.nova.ui.screens.docker.DockerTab
import io.github.nofuturekid.nova.ui.screens.vms.VmsTab
import io.github.nofuturekid.nova.ui.theme.ThemeMode
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real tab composables on Robolectric: each failure reason shows its own title (#247, #217),
 * and a server with no array disks is not offered array actions. Same setup as the store
 * screenshots, but in the normal unit-test run.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w432dp-h768dp-night-400dpi")
class TabFailureRenderingTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) =
        rule.setContent { UnraidTheme(themeMode = ThemeMode.Dark) { content() } }

    private fun error(reason: ErrorReason, message: String = "server text") =
        DomainState.Error(message, detail = null, reason = reason)

    private fun dockerTab(state: DomainState<List<Container>>) = show {
        DockerTab(
            state = state, view = LayoutMode.List, query = "", onQueryChange = {}, onAddServer = {},
            onOpenContainer = {}, onStart = {}, onRestart = {}, onStop = {}, onUpdateAll = {},
        )
    }

    private fun arrayTab(state: DomainState<ArrayInfo>) = show {
        ArrayTab(
            state = state, view = LayoutMode.List, onAddServer = {},
            onStartArray = {}, onStopArray = {}, onStartParity = {}, onPauseParity = {},
            onResumeParity = {}, onCancelParity = {},
        )
    }

    private fun disk(name: String, type: DiskType) = Disk(
        name = name, device = "sd$name", type = type, sizeTb = 4.0, usedTb = 1.0, tempC = 35,
        status = DiskStatus.Ok, model = "Test disk", isSpinning = true, rotational = true,
        numErrors = 0, warningC = null, criticalC = null,
    )

    // WHY: every reason must read differently. A reason that falls back to "Can't reach the server"
    // sends the user to the network when the key, the API version or a switched-off service is at fault.
    @Test fun eachReason_rendersItsTitle() {
        val titles = mapOf(
            ErrorReason.Unreachable to "Can't reach the server",
            ErrorReason.KeyRejected to "The server didn't accept the API key",
            ErrorReason.Forbidden to "This API key can't read containers",
            ErrorReason.ApiTooOld to "This server's Unraid API is too old",
            ErrorReason.ServiceOff to "Docker is turned off",
            ErrorReason.ServerError to "The server couldn't load containers",
        )
        assertTrue("one case per reason", titles.keys == ErrorReason.entries.toSet())
        var reason by mutableStateOf(ErrorReason.Unreachable)
        show {
            DockerTab(
                state = error(reason), view = LayoutMode.List, query = "", onQueryChange = {}, onAddServer = {},
                onOpenContainer = {}, onStart = {}, onRestart = {}, onStop = {}, onUpdateAll = {},
            )
        }
        titles.forEach { (r, title) ->
            reason = r
            rule.waitForIdle()
            rule.onNodeWithText(title).assertIsDisplayed()
        }
    }

    // WHY: #217. The VMs tab of a server with VMs turned off says what to switch on, not "Can't reach the server".
    @Test fun vmsTab_serviceOff_saysVirtualMachinesAreOff() {
        show {
            VmsTab(
                state = error(ErrorReason.ServiceOff), view = LayoutMode.List, onAddServer = {},
                onStart = {}, onResume = {}, onPause = {}, onOpenVm = {},
            )
        }
        rule.onNodeWithText("Virtual machines are turned off").assertIsDisplayed()
        rule.onNodeWithText("Turn them on in the Unraid web UI under Settings → VM Manager.").assertIsDisplayed()
    }

    @Test fun vmsTab_forbidden_namesThePermission() {
        show {
            VmsTab(
                state = error(ErrorReason.Forbidden), view = LayoutMode.List, onAddServer = {},
                onStart = {}, onResume = {}, onPause = {}, onOpenVm = {},
            )
        }
        rule.onNodeWithText("This API key can't read virtual machines").assertIsDisplayed()
        rule.onNodeWithText(
            "It needs the permission VMS:READ_ANY. A key with the VIEWER role can read everything NOVA shows.",
        ).assertIsDisplayed()
    }

    @Test fun arrayTab_apiTooOld_saysWhatToUpdate() {
        arrayTab(error(ErrorReason.ApiTooOld))
        rule.onNodeWithText("This server's Unraid API is too old").assertIsDisplayed()
        rule.onNodeWithText(
            "This screen needs Unraid API 4.30.0 or newer. Update the server to Unraid 7.2.5 or newer.",
        ).assertIsDisplayed()
    }

    // WHY: an unclassified server error must still show what the server said, under a title that
    // does not claim the server is unreachable.
    @Test fun serverError_showsTheServersOwnText() {
        dockerTab(error(ErrorReason.ServerError, "Something odd happened"))
        rule.onNodeWithText("The server couldn't load containers").assertIsDisplayed()
        rule.onNodeWithText("Something odd happened").assertIsDisplayed()
    }

    // WHY: Docker on, nothing installed: the tab used to show only a search box and looked broken.
    @Test fun dockerWithoutContainers_saysSo() {
        dockerTab(DomainState.Content(emptyList()))
        rule.onNodeWithText("No containers").assertIsDisplayed()
        rule.onNodeWithText("Containers you add in the Unraid web UI show up here.").assertIsDisplayed()
    }

    // WHY: VMs on with an empty list stays "No VMs configured": only an ERROR means the service is off.
    @Test fun vmsWithoutMachines_keepsItsEmptyState() {
        show {
            VmsTab(
                state = DomainState.Content(emptyList<Vm>()), view = LayoutMode.List, onAddServer = {},
                onStart = {}, onResume = {}, onPause = {}, onOpenVm = {},
            )
        }
        rule.onNodeWithText("No VMs configured").assertIsDisplayed()
    }

    // WHY: #217. A pools-only server has no array: offering "Start" or a parity check would send
    // commands the server cannot apply. The pools stay listed.
    @Test fun noArrayDisks_hidesArrayActions_andKeepsPools() {
        val poolsOnly = ArrayInfo(
            state = ArrayState.Stopped, totalTb = 0.0, usedTb = 0.0, parity = null,
            disks = listOf(disk("cache", DiskType.Cache)),
        )
        arrayTab(DomainState.Content(poolsOnly))
        rule.onNodeWithText("This server has no array").assertIsDisplayed()
        rule.onNodeWithText("Its storage is in pools.").assertIsDisplayed()
        rule.onNodeWithText("cache").assertIsDisplayed()
        listOf("Start", "Stop", "Check parity").forEach {
            assertFalse("'$it' must not be offered without an array", rule.nodeExists(it))
        }
    }

    // WHY: the guard above must be able to fail. A server WITH array disks keeps its actions
    // (here stopped, so "Start"), otherwise the test would pass for a screen that hides everything.
    @Test fun withArrayDisks_keepsArrayActions() {
        val withArray = ArrayInfo(
            state = ArrayState.Stopped, totalTb = 4.0, usedTb = 1.0, parity = null,
            disks = listOf(disk("parity", DiskType.Parity), disk("disk1", DiskType.Data)),
        )
        arrayTab(DomainState.Content(withArray))
        rule.onNodeWithText("Start").assertIsDisplayed()
        assertFalse(rule.nodeExists("This server has no array"))
    }

    private fun ComposeContentTestRule.nodeExists(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
