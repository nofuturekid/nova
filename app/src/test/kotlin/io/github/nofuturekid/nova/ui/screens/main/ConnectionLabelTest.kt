package io.github.nofuturekid.nova.ui.screens.main

import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.ErrorReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLabelTest {

    // WHY: the pill is also the Local/Remote switch. In #217 every tab failed
    // while the connection test (of the remote URL) passed, and the pill only
    // said "Offline" — the user could not see that the app was polling the
    // other address. The mode has to stay visible when the server is down.
    @Test fun error_keepsModeVisible() {
        assertEquals("Local · Offline", connectionLabel(DomainState.Error("x"), ConnectionMode.Local))
        assertEquals("Remote · Offline", connectionLabel(DomainState.Error("x"), ConnectionMode.Remote))
    }

    @Test fun reachable_showsModeOnly() {
        assertEquals("Local", connectionLabel(DomainState.Loading, ConnectionMode.Local))
        assertEquals("Remote", connectionLabel(DomainState.Content(Unit), ConnectionMode.Remote))
    }

    // WHY: without a server there is no address to switch, so no mode either.
    @Test fun noServer_saysSo() {
        assertEquals("No server", connectionLabel(DomainState.NoServer, ConnectionMode.Remote))
    }

    // WHY: #217. A server with VMs turned off answers every query; the pill must not call it Offline,
    // or the user hunts for a network problem that does not exist.
    @Test fun vmsOff_doesNotTurnOverviewOffline() {
        val overview = firstErrorOrAny(
            DomainState.Content(Unit),
            DomainState.Content(Unit),
            DomainState.Content(Unit),
            DomainState.Error("VMs are not available", reason = ErrorReason.ServiceOff),
        )
        assertEquals("Local", connectionLabel(overview, ConnectionMode.Local))
        assertEquals("Remote", connectionLabel(overview, ConnectionMode.Remote))
    }

    // WHY: the same holds when nothing else has loaded yet, and for every reason that is not about the connection.
    // A redirect is one of them: the server answered, so "Offline" would send the user to the network.
    @Test fun failuresThatAreNotTheConnection_leaveThePillAlone() {
        listOf(
            ErrorReason.Forbidden, ErrorReason.ApiTooOld, ErrorReason.ServiceOff, ErrorReason.Redirected, ErrorReason.ServerError,
        ).forEach {
            assertEquals("$it", "Local", connectionLabel(DomainState.Error("x", reason = it), ConnectionMode.Local))
        }
    }

    // WHY: a real outage anywhere on the Overview still wins over healthy domains, so Offline stays honest.
    @Test fun unreachableDomain_stillWinsOnOverview() {
        val overview = firstErrorOrAny(
            DomainState.Content(Unit),
            DomainState.Error("VMs are not available", reason = ErrorReason.ServiceOff),
            DomainState.Error("Check that the server is online"),
        )
        assertEquals("Local · Offline", connectionLabel(overview, ConnectionMode.Local))
    }

    // WHY: a refused key is a different fix from an outage (edit the key, not the network); the pill says which.
    @Test fun keyRejected_isNamedOnThePill() {
        val rejected = DomainState.Error("API key validation failed", reason = ErrorReason.KeyRejected)
        assertEquals("Local · Key rejected", connectionLabel(rejected, ConnectionMode.Local))
        assertEquals("Remote · Key rejected", connectionLabel(rejected, ConnectionMode.Remote))
    }
}
