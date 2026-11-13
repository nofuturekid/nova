package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.certPromptAfterActive
import io.github.nofuturekid.nova.data.repository.UnraidRepository.CertPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WHY: one prompt per new fingerprint. A prompt raised while the new server had no pin
 * (or raised for a certificate the user has meanwhile trusted) must not stay on screen
 * once the pin for exactly that fingerprint exists; and the security property must
 * survive the cleanup: a CHANGED fingerprint, or any fingerprint that is not the pinned
 * one, still has to be shown (#292).
 */
class StaleCertPromptTest {
    private val server = Server(id = "s1", name = "Tower", hostname = "tower", localUrl = "https://tower", remoteUrl = "", trustSelfSignedLocal = true)

    private fun active(pin: String?, s: Server = server) =
        ActiveServer(s, ConnectionMode.Local, "key", ApiKeyResult.Present("key"), pin)

    private fun untrusted(fp: String, id: String = "s1") = CertPrompt(id, "Tower", fp, null)

    // WHY: the user already trusted this fingerprint in the sheet; asking again is the bug.
    @Test fun promptForTheFingerprintJustPinned_isDropped() {
        assertNull(certPromptAfterActive(untrusted("AA:01"), active("AA:01")))
    }

    // WHY (security): no pin yet means the certificate is NOT trusted; the prompt must stay.
    @Test fun promptWithoutAnyPin_stays() {
        val p = untrusted("AA:01")
        assertEquals(p, certPromptAfterActive(p, active(null)))
    }

    // WHY (security): a different certificate than the pinned one is the "changed" case
    // and must keep asking, with old and new value.
    @Test fun changedFingerprint_stays() {
        val p = CertPrompt("s1", "Tower", presentedSha256 = "BB:02", previousSha256 = "AA:01")
        assertEquals(p, certPromptAfterActive(p, active("AA:01")))
    }

    // WHY (security): a pin for another server never answers this server's prompt.
    @Test fun pinOfAnotherServer_doesNotAnswerThePrompt() {
        val other = server.copy(id = "s2")
        val p = untrusted("AA:01", id = "s1")
        assertEquals(p, certPromptAfterActive(p, active("AA:01", other)))
    }

    // WHY: no prompt, or no active server, must not invent or lose anything.
    @Test fun nothingToAnswer() {
        assertNull(certPromptAfterActive(null, active("AA:01")))
        val p = untrusted("AA:01")
        assertEquals(p, certPromptAfterActive(p, null))
    }
}
