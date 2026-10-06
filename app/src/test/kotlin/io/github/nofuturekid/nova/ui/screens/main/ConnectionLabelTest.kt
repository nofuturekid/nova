package io.github.nofuturekid.nova.ui.screens.main

import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.repository.DomainState
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
}
