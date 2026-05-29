package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.ConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TlsTrustDecisionTest {

    // WHY: a CA-valid cert must always pass regardless of pin state, so a
    // properly-certified local server keeps working without a pin.
    @Test fun caValid_accepts_even_without_pin() {
        assertEquals(TrustDecision.Accept, decidePinnedTrust(defaultTrusts = true, pinned = null, presented = "AA"))
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
}
