package io.github.nofuturekid.nova.data.local

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

private val Context.apiKeyDataStore by preferencesDataStore(name = "unraid_keys")

/**
 * Outcome of reading a stored API key (ADR-0035, an ADR-0024 amendment).
 *
 * The two failure cases must be told apart: [Absent] means no key was
 * ever stored for this server (normal first-run / post-update state);
 * [Undecryptable] means ciphertext IS present but Tink decryption threw
 * — e.g. the Android-Keystore master key was lost (factory reset, app
 * data cleared on some OEMs, Keystore corruption). The latter must NOT
 * be silently flattened to "missing key": the user needs to re-enter
 * the key, and the message has to say so.
 */
sealed interface ApiKeyResult {
    data class Present(val key: String) : ApiKeyResult
    data object Absent : ApiKeyResult
    data object Undecryptable : ApiKeyResult
}

/**
 * Per-server Unraid API keys are sensitive — encrypted at rest with Tink
 * AEAD (AES256-GCM). The keyset is persisted by Tink's
 * [AndroidKeysetManager], wrapped by an Android-Keystore master key.
 * Ciphertext (Base64) lives in a dedicated Preferences DataStore; the
 * server id is passed as associated data so each ciphertext is bound to
 * its server.
 *
 * Replaces the deprecated androidx.security EncryptedSharedPreferences.
 * No migration from the old store — the app is dev-only distributed, so
 * the key is re-entered once after the update. See ADR-0024.
 */
@Singleton
class ApiKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** One lock for the keyset, every encrypt/decrypt/read and the reset (ADR-0035 amendment). */
    private val lock = Mutex()

    /** Guarded by [lock]. */
    private var cachedAead: Aead? = null

    /** Emits whenever any stored ciphertext changes (key-only edits must reach running streams). */
    val ciphertexts: Flow<Map<String, String>> = context.apiKeyDataStore.data
        .map { prefs -> prefs.asMap().entries.associate { (k, v) -> k.name to v.toString() } }
        .distinctUntilChanged()

    private fun aead(): Aead = cachedAead ?: buildAead().also { cachedAead = it }

    private fun buildAead(): Aead {
        AeadConfig.register()
        val prefs = context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE)
        val hadKeyset = prefs.contains(KEYSET_NAME)
        // Without a keyset Tink would silently fall back to a plaintext keyset if the
        // Keystore is unusable; create the master key explicitly so that fails instead.
        if (!hadKeyset && !masterKeyExists()) AndroidKeystoreKmsClient.generateNewAeadKey(MASTER_KEY_URI)
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        ensureKeystoreWrapped(hadKeyset, manager.isUsingKeystore) { prefs.edit().remove(KEYSET_NAME).commit() }
        return manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun masterKeyExists(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .containsAlias(MASTER_KEY_URI.removePrefix("android-keystore://"))

    /**
     * Delete everything that depends on the keyset: the keyset prefs entry, the
     * Keystore master key and every stored ciphertext. Never called
     * automatically; only from the explicit user action (see [resetKeyStorage]).
     */
    private suspend fun resetKeyMaterial() {
        cachedAead = null
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().remove(KEYSET_NAME).commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MASTER_KEY_URI.removePrefix("android-keystore://"))
        }
        context.apiKeyDataStore.edit { it.clear() }
    }

    /**
     * Explicit, user-initiated recovery (ADR-0035 amendment): deletes the keyset,
     * the master key and ALL stored API keys. `put` never does this on its own.
     */
    suspend fun resetKeyStorage() = withContext(Dispatchers.IO) { resetUnderLock(lock) {
        // Not cancellable: closing the sheet mid-reset must not leave the keyset gone
        // while ciphertexts remain.
        withContext(NonCancellable) { resetKeyMaterial() }
    } }

    /**
     * Encrypt and store. A key-storage failure (building the AEAD or encrypting)
     * is thrown as [KeyStorageException]; stored keys are never touched.
     */
    suspend fun put(serverId: String, key: String) = withContext(Dispatchers.IO) {
        putKey(
            lock = lock,
            encrypt = {
                try {
                    aead().encrypt(key.toByteArray(Charsets.UTF_8), serverId.toByteArray(Charsets.UTF_8))
                } catch (e: Exception) {
                    cachedAead = null
                    throw e
                }
            },
            store = { ct ->
                val enc = Base64.encodeToString(ct, Base64.NO_WRAP)
                context.apiKeyDataStore.edit { it[stringPreferencesKey(serverId)] = enc }
            },
        )
    }

    /**
     * Read + decrypt, distinguishing "never stored" from "stored but
     * undecryptable" (ADR-0035). Never logs key material; on decrypt
     * failure the exception is swallowed deliberately (it could carry
     * ciphertext-derived data) and reported only as [Undecryptable].
     */
    suspend fun getResult(serverId: String): ApiKeyResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val enc = context.apiKeyDataStore.data.first()[stringPreferencesKey(serverId)]
                ?: return@withLock ApiKeyResult.Absent
            runCatching {
                val ct = Base64.decode(enc, Base64.NO_WRAP)
                String(aead().decrypt(ct, serverId.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
            }.fold(
                onSuccess = { ApiKeyResult.Present(it) },
                // Ciphertext present but decrypt threw → distinct state, not null.
                onFailure = { ApiKeyResult.Undecryptable },
            )
        }
    }

    suspend fun get(serverId: String): String? =
        (getResult(serverId) as? ApiKeyResult.Present)?.key

    suspend fun remove(serverId: String) = withContext(Dispatchers.IO) {
        context.apiKeyDataStore.edit { it.remove(stringPreferencesKey(serverId)) }
        Unit
    }

    /** The keyset or Keystore master key could not be built or used; stored keys are untouched. */
    class KeyStorageException(cause: Throwable) : GeneralSecurityException("Key storage failed", cause)

    internal companion object {
        private const val KEYSET_NAME = "unraid_apikey_keyset"
        private const val KEYSET_PREFS = "unraid_apikey_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://unraid_apikey_master"

        /**
         * Encrypt and store under [lock]. A failure of [encrypt] is rethrown as
         * [KeyStorageException]; nothing is deleted. Test seam (same-module
         * `internal`) so the logic runs on the JVM without Android Keystore.
         */
        internal suspend fun putKey(
            lock: Mutex,
            encrypt: () -> ByteArray,
            store: suspend (ByteArray) -> Unit,
        ) = lock.withLock {
            val ct = try {
                encrypt()
            } catch (e: Exception) {
                throw KeyStorageException(e)
            }
            store(ct)
        }

        /** Run the explicit reset under the same lock as put/get. */
        internal suspend fun resetUnderLock(lock: Mutex, reset: suspend () -> Unit) = lock.withLock { reset() }

        /**
         * A freshly generated keyset that is not Keystore-wrapped means Tink fell
         * back to storing it unencrypted: remove it and fail. An existing keyset
         * (possibly an old plaintext one) keeps loading.
         */
        internal fun ensureKeystoreWrapped(hadKeyset: Boolean, usingKeystore: Boolean, removeKeyset: () -> Unit) {
            if (!hadKeyset && !usingKeystore) {
                removeKeyset()
                throw GeneralSecurityException("Android Keystore unavailable; refusing an unencrypted keyset")
            }
        }
    }
}
