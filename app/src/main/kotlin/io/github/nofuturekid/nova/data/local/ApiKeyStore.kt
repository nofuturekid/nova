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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "unraid_apikey_keyset", "unraid_apikey_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://unraid_apikey_master")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    suspend fun put(serverId: String, key: String) = withContext(Dispatchers.IO) {
        val ct = aead.encrypt(key.toByteArray(Charsets.UTF_8), serverId.toByteArray(Charsets.UTF_8))
        val enc = Base64.encodeToString(ct, Base64.NO_WRAP)
        context.apiKeyDataStore.edit { it[stringPreferencesKey(serverId)] = enc }
        Unit
    }

    /**
     * Read + decrypt, distinguishing "never stored" from "stored but
     * undecryptable" (ADR-0035). Never logs key material; on decrypt
     * failure the exception is swallowed deliberately (it could carry
     * ciphertext-derived data) and reported only as [Undecryptable].
     */
    suspend fun getResult(serverId: String): ApiKeyResult = withContext(Dispatchers.IO) {
        val enc = context.apiKeyDataStore.data.first()[stringPreferencesKey(serverId)]
            ?: return@withContext ApiKeyResult.Absent
        runCatching {
            val ct = Base64.decode(enc, Base64.NO_WRAP)
            String(aead.decrypt(ct, serverId.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        }.fold(
            onSuccess = { ApiKeyResult.Present(it) },
            // Ciphertext present but decrypt threw → distinct state, not null.
            onFailure = { ApiKeyResult.Undecryptable },
        )
    }

    suspend fun get(serverId: String): String? =
        (getResult(serverId) as? ApiKeyResult.Present)?.key

    suspend fun remove(serverId: String) = withContext(Dispatchers.IO) {
        context.apiKeyDataStore.edit { it.remove(stringPreferencesKey(serverId)) }
        Unit
    }
}
