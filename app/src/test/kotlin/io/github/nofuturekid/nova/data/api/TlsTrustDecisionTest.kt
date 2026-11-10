package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.ConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException

// NOTE: the JVM default trust manager differs from the device one. On Android
// the app's per-domain network security config makes the platform trust
// manager's hostname-unaware check always throw, so `defaultTrusts` is false
// there. These tests pin the decision logic itself, independent of that config.
class TlsTrustDecisionTest {

    // WHY: a CA-valid chain keeps working without a pin (properly certified
    // local server) -- but only because the hostname check below still applies.
    @Test fun caValid_correctHost_accepts() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(defaultTrusts = true, pinned = null, presented = "AA"))
        assertTrue(hostnameTrusted(pinned = null, presented = "AA") { true })
    }

    // WHY: local trust must not depend on the unrelated network security config:
    // a publicly trusted cert for another domain must not carry the API key,
    // pin or no pin. The pin is the anchor,
    // not the CA chain (ADR-0041), so a non-matching leaf gets the stock check.
    @Test fun caValid_wrongHost_pinSet_rejects() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(true, "AA:BB", "CC:DD")) // chain half only
        assertFalse(hostnameTrusted(pinned = "AA:BB", presented = "CC:DD") { false })
    }
    @Test fun caValid_wrongHost_noPin_rejects() {
        assertFalse(hostnameTrusted(pinned = null, presented = "CC:DD") { false })
    }

    // WHY: self-signed LAN certs rarely match the typed host; the pinned leaf
    // is the only case where the name check may be skipped.
    @Test fun selfSigned_leafEqualsPin_anyHost_accepts() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(false, "AA:BB", "aa:bb"))
        assertTrue(hostnameTrusted(pinned = "AA:BB", presented = "aa:bb") { false })
    }

    // WHY: changed-cert path must stay blocked, and a non-pinned leaf must
    // not get hostname relaxation either.
    @Test fun selfSigned_leafNotPin_rejects() {
        assertEquals(TrustDecision.Changed("AA:BB", "CC:DD"), decidePinnedTrust(false, "AA:BB", "CC:DD"))
        assertFalse(hostnameTrusted(pinned = "AA:BB", presented = "CC:DD") { false })
    }

    // WHY: a pin match must short-circuit the default verifier (it would fail
    // on a self-signed IP cert), a mismatch must consult it.
    @Test fun hostnameTrusted_consultsDefaultOnlyWithoutPinMatch() {
        var calls = 0
        assertTrue(hostnameTrusted("AA", "AA") { calls++; false })
        assertEquals(0, calls)
        hostnameTrusted("AA", "BB") { calls++; false }
        assertEquals(1, calls)
    }

    // WHY: first untrusted cert must surface its fingerprint for user confirm,
    // never silently trust (the locked TOFU decision).
    @Test fun untrusted_noPin_isFirstUse_withFingerprint() {
        assertEquals(TrustDecision.FirstUse("AA:BB"), decidePinnedTrust(false, null, "AA:BB"))
    }

    // WHY: a matching pin is the steady-state accept path.
    @Test fun untrusted_matchingPin_accepts() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(false, "AA:BB", "AA:BB"))
    }

    // WHY: matching must be case-insensitive (hex casing must not cause a
    // false "certificate changed" lockout).
    @Test fun untrusted_matchingPin_caseInsensitive_accepts() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(false, "aa:bb", "AA:BB"))
    }

    // WHY: a different cert under an existing pin is the MITM/rotation signal —
    // must block and report both fingerprints. This is the core security property.
    @Test fun untrusted_differentPin_isChanged() {
        assertEquals(TrustDecision.Changed("AA:BB", "CC:DD"), decidePinnedTrust(false, "AA:BB", "CC:DD"))
    }

    // WHY: the entire "only for local" guarantee lives in trustFor. Relaxed
    // trust must appear ONLY for local + opt-in + https; never remote, http,
    // or flag-off (otherwise self-signed trust could leak to the remote or
    // GitHub-update path).
    @Test fun trustFor_local_https_optIn_isPinned() {
        val t = trustFor(ConnectionMode.Local, trustSelfSignedLocal = true, url = "https://192.168.11.2", pin = "AA")
        assertEquals(TlsTrust.PinnedSelfSigned("AA"), t)
    }
    @Test fun trustFor_remote_isAlwaysDefault() {
        assertEquals(TlsTrust.Default, trustFor(ConnectionMode.Remote, true, "https://x.unraid.net", "AA"))
    }
    @Test fun trustFor_local_http_isDefault() {
        assertEquals(TlsTrust.Default, trustFor(ConnectionMode.Local, true, "http://192.168.11.2", null))
    }
    @Test fun trustFor_flagOff_isDefault() {
        assertEquals(TlsTrust.Default, trustFor(ConnectionMode.Local, false, "https://192.168.11.2", "AA"))
    }

    // WHY: an empty/malformed chain must be a clean rejection (CertificateException),
    // never an unchecked ArrayIndexOutOfBoundsException that the SSL engine might mishandle.
    @Test fun emptyChain_throwsCertificateException() {
        val tm = LocalPinningTrustManager(systemDefaultTrustManager(), pinnedSha256 = null, acceptFirstUse = false)
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    // WHY: cert issues arrive wrapped by Apollo/OkHttp, so the cause-walk must
    // find them at depth, not only as direct causes.
    @Test fun certIssue_findsDeeplNestedCertException() {
        val inner = CertificateChangedException("AA", "BB")
        val wrapped = RuntimeException("x", java.io.IOException("y", inner))
        assertEquals(CertIssue.Changed("AA", "BB"), wrapped.certIssue())
    }
}
