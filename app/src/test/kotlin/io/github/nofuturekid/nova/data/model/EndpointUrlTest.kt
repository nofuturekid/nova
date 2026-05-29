package io.github.nofuturekid.nova.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointUrlTest {

    // WHY: an empty host must stay an unset endpoint (parity with old blank URL).
    @Test fun compose_blankHost_isEmpty() {
        assertEquals("", EndpointUrl.compose("", ssl = true))
        assertEquals("", EndpointUrl.compose("   ", ssl = false))
    }

    @Test fun compose_http_and_https() {
        assertEquals("http://192.168.11.2", EndpointUrl.compose("192.168.11.2", ssl = false))
        assertEquals("https://192.168.11.2", EndpointUrl.compose("192.168.11.2", ssl = true))
    }

    // WHY: a custom port typed into the host must survive composition.
    @Test fun compose_keepsPort() {
        assertEquals("https://192.168.11.2:8443", EndpointUrl.compose("192.168.11.2:8443", ssl = true))
    }

    // WHY: editing an existing server must round-trip the stored URL back into
    // host + toggle without rewriting it.
    @Test fun parse_roundTrips_storedUrls() {
        assertEquals(EndpointUrl("192.168.11.2", ssl = false), EndpointUrl.parse("http://192.168.11.2", defaultSsl = true))
        assertEquals(EndpointUrl("unraid-01.kroll-home.de", ssl = true), EndpointUrl.parse("https://unraid-01.kroll-home.de", defaultSsl = false))
        assertEquals(EndpointUrl("192.168.11.2:8443", ssl = true), EndpointUrl.parse("https://192.168.11.2:8443", defaultSsl = false))
    }

    // WHY: a blank stored URL takes the per-endpoint default SSL state.
    @Test fun parse_blank_usesDefault() {
        assertEquals(EndpointUrl("", ssl = true), EndpointUrl.parse("", defaultSsl = true))
        assertEquals(EndpointUrl("", ssl = false), EndpointUrl.parse("", defaultSsl = false))
    }

    // WHY: legacy URLs with a trailing path must not leak the path into the host.
    @Test fun parse_stripsTrailingPath() {
        assertEquals(EndpointUrl("192.168.11.2", ssl = false), EndpointUrl.parse("http://192.168.11.2/", defaultSsl = true))
    }

    // WHY: a stored URL with a mixed-case scheme must still parse host + ssl
    // correctly (the old four-exact-prefix strip left "Https://host" as the host).
    @Test fun parse_mixedCaseScheme() {
        assertEquals(EndpointUrl("tower.local", ssl = true), EndpointUrl.parse("Https://tower.local", defaultSsl = false))
        assertEquals(EndpointUrl("192.168.11.2", ssl = false), EndpointUrl.parse("HTTP://192.168.11.2", defaultSsl = true))
    }

    // WHY: if the user pastes a full URL into the host field, compose must not
    // produce a double scheme like "https://http://host"; it strips the pasted
    // scheme (and any path) and applies the SSL toggle.
    @Test fun compose_stripsPastedSchemeAndPath() {
        assertEquals("https://192.168.11.2", EndpointUrl.compose("http://192.168.11.2", ssl = true))
        assertEquals("https://192.168.11.2:8443", EndpointUrl.compose("https://192.168.11.2:8443/graphql", ssl = true))
    }

    // WHY: save() derives hostname from the host field; a pasted URL must not
    // yield "http:" as the hostname.
    @Test fun normalizeHost_stripsSchemeAndPath() {
        assertEquals("192.168.11.2", EndpointUrl.normalizeHost("http://192.168.11.2/graphql"))
        assertEquals("tower.local", EndpointUrl.normalizeHost("  tower.local  "))
        assertEquals("", EndpointUrl.normalizeHost(""))
    }
}
