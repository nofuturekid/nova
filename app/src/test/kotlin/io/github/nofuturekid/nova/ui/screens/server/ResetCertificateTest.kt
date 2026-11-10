package io.github.nofuturekid.nova.ui.screens.server

import io.github.nofuturekid.nova.ui.screens.server.AddEditServerViewModel.Companion.afterCertReset
import io.github.nofuturekid.nova.ui.screens.server.AddEditServerViewModel.Companion.pinToPersist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Reset certificate" is the recovery control for a bad TOFU pin. It must
 * not be silently undone: the editing sheet seeds `pendingLocalCertSha256`
 * with the stored pin, and Save / Test both read it.
 *
 * Drives the production decision functions the ViewModel uses (internal
 * companion seam); the ViewModel itself is not constructed because its
 * repositories are final Hilt classes backed by DataStore (no JVM fakes).
 */
class ResetCertificateTest {
    private val loaded = AddEditUiState(
        name = "Tower", trustSelfSignedLocal = true,
        hasStoredPin = true, pendingLocalCertSha256 = "AA:BB",
    )

    // WHY: Reset -> Save must not write the old fingerprint back.
    @Test fun reset_then_save_persistsNoPin() {
        assertEquals("AA:BB", pinToPersist(loaded)) // sanity: without reset it would re-pin
        assertNull(pinToPersist(afterCertReset(loaded)))
    }

    // WHY: Reset -> Test must run with no pin (testLocal passes
    // pendingLocalCertSha256), so the first-use prompt appears again.
    @Test fun reset_then_test_hasNoPinToMatch() {
        val s = afterCertReset(loaded)
        assertNull(s.pendingLocalCertSha256)
        assertEquals(false, s.hasStoredPin)
    }

    // WHY: a freshly confirmed fingerprint after Reset is still pinned on Save.
    @Test fun confirmAfterReset_isPersisted() {
        val s = afterCertReset(loaded).copy(pendingLocalCertSha256 = "CC:DD")
        assertEquals("CC:DD", pinToPersist(s))
    }

    // WHY: trust switched off => never persist a pin (mirrors upsert's clearPin).
    @Test fun trustOff_neverPersists() {
        assertNull(pinToPersist(loaded.copy(trustSelfSignedLocal = false)))
    }
}
