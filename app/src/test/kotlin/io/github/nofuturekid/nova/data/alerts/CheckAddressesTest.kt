package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.api.TlsTrust
import io.github.nofuturekid.nova.data.model.Server
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHY: a pinned self-signed certificate is only a good reason to trust a connection on the
 * server's own local address (ADR-0041). The background check tries the remote address too, so
 * it must never hand the pin, or any relaxed trust, to it: a stranger's server answering for the
 * remote name would be trusted. The in-app Local/Remote switch is ignored on purpose; it can say
 * "Remote" while the phone is at home.
 */
class CheckAddressesTest {
    private fun server(local: String, remote: String, selfSigned: Boolean = true) = Server(
        id = "s", name = "Tower", hostname = "tower", localUrl = local, remoteUrl = remote, trustSelfSignedLocal = selfSigned,
    )

    @Test fun local_first_then_remote_and_the_pin_only_on_local_https() {
        val addresses = checkAddresses(server("https://tower.local", "https://tower.example.org"), pin = "AA:BB")

        assertEquals(
            listOf(
                CheckAddress("https://tower.local", TlsTrust.PinnedSelfSigned("AA:BB")),
                CheckAddress("https://tower.example.org", TlsTrust.Default),
            ),
            addresses,
        )
    }

    @Test fun plain_http_and_servers_without_the_opt_in_get_default_trust() {
        assertEquals(
            listOf(CheckAddress("http://192.168.1.2", TlsTrust.Default)),
            checkAddresses(server("http://192.168.1.2", ""), pin = "AA:BB"),
        )
        assertEquals(
            listOf(CheckAddress("https://tower.local", TlsTrust.Default)),
            checkAddresses(server("https://tower.local", "", selfSigned = false), pin = "AA:BB"),
        )
    }

    @Test fun an_unpinned_opt_in_server_is_checked_with_no_pin_and_so_fails_closed() {
        // The pin is null until the user accepted a certificate in the app; the check never pins.
        assertEquals(
            listOf(CheckAddress("https://tower.local", TlsTrust.PinnedSelfSigned(null))),
            checkAddresses(server("https://tower.local", ""), pin = null),
        )
    }

    @Test fun a_blank_or_identical_remote_is_not_tried_twice_and_a_blank_local_is_skipped() {
        assertEquals(1, checkAddresses(server("https://tower.local", ""), null).size)
        assertEquals(1, checkAddresses(server("https://tower.local", "https://tower.local/"), null).size)
        assertEquals(
            listOf(CheckAddress("https://remote.example.org", TlsTrust.Default)),
            checkAddresses(server("", "https://remote.example.org"), "AA:BB"),
        )
        assertEquals(emptyList<CheckAddress>(), checkAddresses(server("", ""), null))
    }
}
