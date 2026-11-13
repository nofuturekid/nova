package io.github.nofuturekid.nova.ui.screens.overview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.LiveMetrics
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.ServerInfo
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.ErrorReason
import io.github.nofuturekid.nova.ui.theme.ThemeMode
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #356 (reported on 0.1.48): with Docker or the VM manager switched off on the server, or a key
 * that may not read them, the Overview never showed the "Needs attention" card, even when the
 * array is degraded. The server is reachable in every one of these cases. Cases B and C fail
 * on the released tag, because there any Error on a domain hid the card.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w432dp-h768dp-night-400dpi")
class NeedsAttentionWithServicesOffTest {

    @get:Rule val rule = createComposeRule()

    private val server = Server("s", "Tower", "tower.example.net", "http://192.0.2.10", "https://tower.example.net")
    private val info = ServerInfo("Tower", "1d", "7.2.0", "6.12", "CPU", 4, 8, 4.0, 16.0)
    private val degraded = ArrayInfo(ArrayState.Error, totalTb = 8.0, usedTb = 2.0, parity = null, disks = emptyList())
    private val noContainers: DomainState<List<Container>> = DomainState.Content(emptyList())
    private val noVms: DomainState<List<Vm>> = DomainState.Content(emptyList())

    private fun off(reason: ErrorReason, message: String) = DomainState.Error(message, detail = null, reason = reason)
    private val vmsOff = off(ErrorReason.ServiceOff, "Failed to retrieve VM domains: VMs are not available")
    private val dockerOff = off(ErrorReason.ServiceOff, "Docker socket unavailable.")

    private fun show(docker: DomainState<List<Container>>, vms: DomainState<List<Vm>>) {
        rule.setContent {
            UnraidTheme(themeMode = ThemeMode.Dark) {
                OverviewTab(
                    infoState = DomainState.Content(info),
                    metricsState = DomainState.Content(LiveMetrics(10.0, 20.0, 5.0, 2.0)),
                    arrayState = DomainState.Content(degraded),
                    dockerState = docker,
                    vmsState = vms,
                    server = server,
                    onAddServer = {},
                )
            }
        }
    }

    private fun assertCardShown() {
        rule.onNodeWithText("Needs attention").assertIsDisplayed()
        rule.onNodeWithText("Array is degraded").assertIsDisplayed()
    }

    // A: control, so B and C cannot pass for a card that never depends on the services.
    @Test fun a_degraded_array_shows_the_card_when_every_service_answers() {
        show(noContainers, noVms)
        assertCardShown()
    }

    // B
    @Test fun the_card_is_shown_although_VMs_are_off() {
        show(noContainers, vmsOff)
        assertCardShown()
    }

    // C
    @Test fun the_card_is_shown_although_Docker_is_off() {
        show(dockerOff, noVms)
        assertCardShown()
    }

    // The "permission the key lacks" half of #356, rendered once for each domain.
    @Test fun the_card_is_shown_although_the_key_may_not_read_Docker() {
        show(off(ErrorReason.Forbidden, "Forbidden resource"), noVms)
        assertCardShown()
    }

    @Test fun the_card_is_shown_although_the_key_may_not_read_VMs() {
        show(noContainers, off(ErrorReason.Forbidden, "Forbidden resource"))
        assertCardShown()
    }
}
