package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.api.TlsTrust
import io.github.nofuturekid.nova.data.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun servers_without_the_opt_in_get_default_trust() {
        assertEquals(
            listOf(CheckAddress("https://tower.local", TlsTrust.Default)),
            checkAddresses(server("https://tower.local", "", selfSigned = false), pin = "AA:BB"),
        )
    }

    @Test fun a_plain_http_address_is_never_a_check_address() {
        // WHY: the check runs unattended every 15 min to 3 h. An http address would get the API
        // key in clear text, to whatever answers at that name or IP in the current network.
        assertEquals(emptyList<CheckAddress>(), checkAddresses(server("http://192.168.1.2", ""), pin = "AA:BB"))
        assertEquals(emptyList<CheckAddress>(), checkAddresses(server("http://192.168.1.2", "http://tower.example.org"), pin = null))
    }

    @Test fun local_http_with_an_https_remote_is_checked_through_the_remote_only() {
        assertEquals(
            listOf(CheckAddress("https://tower.example.org", TlsTrust.Default)),
            checkAddresses(server("http://192.168.1.2", "https://tower.example.org"), pin = "AA:BB"),
        )
    }

    @Test fun local_https_with_an_http_remote_is_checked_through_the_local_only() {
        assertEquals(
            listOf(CheckAddress("https://tower.local", TlsTrust.PinnedSelfSigned("AA:BB"))),
            checkAddresses(server("https://tower.local", "http://tower.example.org"), pin = "AA:BB"),
        )
    }

    @Test fun the_scheme_is_parsed_not_prefix_matched() {
        // WHY: a URL is https by what OkHttp makes of it, so an upper-case scheme counts and
        // look-alikes ("httpsfoo://", "https:/") do not.
        assertEquals(
            listOf(CheckAddress("HTTPS://tower.local", TlsTrust.Default)),
            checkAddresses(server("HTTPS://tower.local", "", selfSigned = false), pin = null),
        )
        assertEquals(emptyList<CheckAddress>(), checkAddresses(server("httpsfoo://tower.local", "tower.local"), pin = null))
    }

    @Test fun cleartext_only_means_there_is_an_address_but_none_is_https() {
        assertTrue(server("http://192.168.1.2", "").hasCleartextOnlyAddresses())
        assertTrue(server("http://192.168.1.2", "http://tower.example.org").hasCleartextOnlyAddresses())
        assertFalse(server("http://192.168.1.2", "https://tower.example.org").hasCleartextOnlyAddresses())
        assertFalse(server("", "").hasCleartextOnlyAddresses())
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
