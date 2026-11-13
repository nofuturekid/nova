package io.github.nofuturekid.nova.ui.screens.overview

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.ErrorReason
import io.github.nofuturekid.nova.ui.theme.ThemeMode
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Overview names why it failed, and a switched-off Docker or VM service does not make the
 * server look offline (#217, #247). Before, every cause of a total failure read "Can't reach the
 * server", and ANY Error on a domain hid the "Needs attention" card although the server answered.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w432dp-h768dp-night-400dpi")
class OverviewFailureTest {

    @get:Rule
    val rule = createComposeRule()

    private fun error(reason: ErrorReason, message: String = "server text") =
        DomainState.Error(message, detail = null, reason = reason)

    private fun show(content: @Composable () -> Unit) =
        rule.setContent { UnraidTheme(themeMode = ThemeMode.Dark) { content() } }

    private fun overview(
        info: DomainState<io.github.nofuturekid.nova.data.model.ServerInfo>,
        array: DomainState<io.github.nofuturekid.nova.data.model.ArrayInfo>,
        docker: DomainState<List<io.github.nofuturekid.nova.data.model.Container>>,
        vms: DomainState<List<io.github.nofuturekid.nova.data.model.Vm>>,
        metrics: DomainState<io.github.nofuturekid.nova.data.model.LiveMetrics> = DomainState.Loading,
    ) = show {
        OverviewTab(
            infoState = info, metricsState = metrics, arrayState = array, dockerState = docker, vmsState = vms,
            server = null, onAddServer = {},
        )
    }

    private fun ComposeContentTestRule.exists(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // WHY: a rejected key sent the user to check the network. The Overview now gives the tab's own title and next step.
    @Test fun everyDomainFailed_showsTheReason_notAlwaysCantReach() {
        overview(
            info = error(ErrorReason.KeyRejected), array = error(ErrorReason.KeyRejected),
            docker = error(ErrorReason.KeyRejected), vms = error(ErrorReason.KeyRejected),
            metrics = error(ErrorReason.KeyRejected),
        )
        rule.onNodeWithText("The server didn't accept the API key").assertIsDisplayed()
        assertFalse(rule.exists("Can't reach the server"))
    }

    // WHY: the guard above must be able to fail: an unreachable server still reads "Can't reach the server".
    @Test fun everyDomainFailed_unreachableStillSaysSo() {
        overview(
            info = error(ErrorReason.Unreachable, "Check that the server is online"), array = error(ErrorReason.Unreachable),
            docker = error(ErrorReason.Unreachable), vms = error(ErrorReason.Unreachable),
            metrics = error(ErrorReason.Unreachable),
        )
        rule.onNodeWithText("Can't reach the server").assertIsDisplayed()
    }

    // WHY: #357. A redirect is not a connection problem: the server answered. The Overview says what the
    // tabs say (the address needs to change, and where to) and does not claim the server is unreachable.
    @Test fun everyDomainFailed_redirected_namesTheTarget_notCantReach() {
        val message = "The server redirects to https://tower.example"
        overview(
            info = error(ErrorReason.Redirected, message), array = error(ErrorReason.Redirected, message),
            docker = error(ErrorReason.Redirected, message), vms = error(ErrorReason.Redirected, message),
            metrics = error(ErrorReason.Redirected, message),
        )
        rule.onNodeWithText("The address needs to change").assertIsDisplayed()
        rule.onNodeWithText(message).assertIsDisplayed()
        assertFalse(rule.exists("Can't reach the server"))
    }

    // WHY: #217. Docker and VMs switched off, the server answers and the array has a problem: the
    // user must still see "Needs attention". A stopped array is the problem here.
    @Test fun dockerAndVmsOff_keepsTheNeedsAttentionCard() {
        overview(
            info = DomainState.Content(DemoFixtures.info),
            array = DomainState.Content(DemoFixtures.array.copy(state = ArrayState.Stopped)),
            docker = error(ErrorReason.ServiceOff), vms = error(ErrorReason.ServiceOff),
        )
        assertTrue(rule.exists("Needs attention"))
        assertTrue(rule.exists("Array is not started"))
    }

    // WHY: the other half, so the test above cannot pass for a card that is always shown: a server
    // that is really unreachable has nothing trustworthy to flag.
    @Test fun unreachableDomain_hidesTheNeedsAttentionCard() {
        overview(
            info = DomainState.Content(DemoFixtures.info),
            array = DomainState.Content(DemoFixtures.array.copy(state = ArrayState.Stopped)),
            docker = error(ErrorReason.Unreachable), vms = DomainState.Loading,
        )
        assertFalse(rule.exists("Needs attention"))
    }

    // WHY: a rejected key is a connection problem too (the pill says "Key rejected"): nothing to flag either.
    @Test fun rejectedKey_hidesTheNeedsAttentionCard() {
        overview(
            info = DomainState.Content(DemoFixtures.info),
            array = DomainState.Content(DemoFixtures.array.copy(state = ArrayState.Stopped)),
            docker = error(ErrorReason.KeyRejected), vms = DomainState.Loading,
        )
        assertFalse(rule.exists("Needs attention"))
    }

    // WHY: the offline rule is the pure heart of the above; pin it for every reason.
    @Test fun offlineIsDecidedByTheReason() {
        ErrorReason.entries.forEach { reason ->
            assertEquals(
                reason.toString(),
                reason == ErrorReason.Unreachable || reason == ErrorReason.KeyRejected,
                isOffline(DomainState.Content(1), error(reason)),
            )
        }
        assertFalse("no error, not offline", isOffline(DomainState.Content(1), DomainState.Loading))
    }

    // WHY: when the server is unreachable the other domains fail BECAUSE of it; show that cause, not
    // whichever domain happens to come first.
    @Test fun failureShown_prefersAConnectionProblem() {
        val picked = overviewFailure(error(ErrorReason.ServiceOff), error(ErrorReason.KeyRejected), error(ErrorReason.ServerError))
        assertEquals(ErrorReason.KeyRejected, picked!!.reason)
        assertEquals(
            ErrorReason.ServiceOff,
            overviewFailure(DomainState.Loading, error(ErrorReason.ServiceOff), error(ErrorReason.ServerError))!!.reason,
        )
        assertNull(overviewFailure(DomainState.Loading, DomainState.Content(1)))
    }
}
