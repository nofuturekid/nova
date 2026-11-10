package io.github.nofuturekid.nova.data.local

import io.github.nofuturekid.nova.data.local.ApiKeyStore.Companion.ensureKeystoreWrapped
import io.github.nofuturekid.nova.data.local.ApiKeyStore.Companion.putKey
import io.github.nofuturekid.nova.data.local.ApiKeyStore.Companion.resetUnderLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * ADR-0035 amendment. Keystore errors cannot be told apart reliably from a
 * lost master key, so `put` NEVER deletes key material on its own: a failing
 * save surfaces an error and every stored key stays readable. Wiping is an
 * explicit user action only.
 *
 * Drives the production bodies through the internal companion seams; the real
 * Android Keystore / Tink / DataStore parts are not JVM-testable.
 */
class ApiKeyStoreRecoveryTest {
    private val ct = byteArrayOf(1, 2, 3)

    /** Fake storage; `broken` models an unusable keyset until the explicit reset. */
    private class Fake {
        val stored = mutableMapOf("other-server" to byteArrayOf(9))
        var broken = true
        var resets = 0
        suspend fun reset() { resets++; stored.clear(); broken = false }
    }

    // WHY: a failing Keystore (transient or not) must not wipe other servers' keys.
    @Test fun saveFailure_leavesOtherKeysReadable_andDoesNotReset() = runTest {
        val f = Fake()
        val e = assertThrows(ApiKeyStore.KeyStorageException::class.java) {
            kotlinx.coroutines.runBlocking {
                putKey(Mutex(), { throw ProviderException("System error") }) { f.stored["s"] = it }
            }
        }
        assertTrue(e.cause is ProviderException)
        assertEquals(0, f.resets)
        assertArrayEquals(byteArrayOf(9), f.stored["other-server"])
        assertFalse(f.stored.containsKey("s"))
    }

    // WHY: the reported crash -- after the explicit reset the save works again.
    @Test fun explicitReset_thenSaveSucceeds() = runTest {
        val f = Fake()
        val lock = Mutex()
        val encrypt = { if (f.broken) throw GeneralSecurityException("keyset unreadable") else ct }
        assertThrows(ApiKeyStore.KeyStorageException::class.java) {
            kotlinx.coroutines.runBlocking { putKey(lock, encrypt) { f.stored["s"] = it } }
        }
        resetUnderLock(lock, f::reset)
        putKey(lock, encrypt) { f.stored["s"] = it }
        assertEquals(1, f.resets)
        assertArrayEquals(ct, f.stored["s"])
        assertFalse(f.stored.containsKey("other-server")) // reset deletes all stored keys
    }

    // WHY: racing saves must not interleave; both keys survive.
    @Test fun racingPuts_bothKeysSurvive() = runTest {
        val f = Fake()
        f.broken = false
        val lock = Mutex()
        listOf("a", "b").map { id ->
            async { putKey(lock, { id.toByteArray() }) { delay(5); f.stored[id] = it } }
        }.awaitAll()
        assertArrayEquals("a".toByteArray(), f.stored["a"])
        assertArrayEquals("b".toByteArray(), f.stored["b"])
    }

    // WHY: a reset must wait for an in-flight put (and vice versa), not run inside it.
    @Test fun reset_isSerializedWithPut() = runTest {
        val f = Fake()
        f.broken = false
        val lock = Mutex()
        val order = mutableListOf<String>()
        val p = async { putKey(lock, { ct }) { order += "put-start"; delay(10); f.stored["s"] = it; order += "put-end" } }
        val r = async { resetUnderLock(lock) { order += "reset" } }
        listOf(p, r).awaitAll()
        assertEquals(listOf("put-start", "put-end", "reset"), order)
    }

    // WHY: Tink silently stores a NEW keyset unencrypted if the Keystore is unusable;
    // that must fail and leave no keyset behind.
    @Test fun freshKeyset_notKeystoreWrapped_failsAndRemovesIt() {
        var removed = false
        assertThrows(GeneralSecurityException::class.java) {
            ensureKeystoreWrapped(hadKeyset = false, usingKeystore = false) { removed = true }
        }
        assertTrue(removed)
    }

    // WHY: existing installs with an old plaintext keyset must keep loading.
    @Test fun existingKeyset_isNeverRemoved() {
        var removed = false
        ensureKeystoreWrapped(hadKeyset = true, usingKeystore = false) { removed = true }
        ensureKeystoreWrapped(hadKeyset = false, usingKeystore = true) { removed = true }
        assertFalse(removed)
    }
}
