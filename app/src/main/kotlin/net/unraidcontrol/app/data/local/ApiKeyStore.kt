package net.unraidcontrol.app.data.local

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
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
            .getPrimitive(Aead::class.java)
    }

    suspend fun put(serverId: String, key: String) = withContext(Dispatchers.IO) {
        val ct = aead.encrypt(key.toByteArray(Charsets.UTF_8), serverId.toByteArray(Charsets.UTF_8))
        val enc = Base64.encodeToString(ct, Base64.NO_WRAP)
        context.apiKeyDataStore.edit { it[stringPreferencesKey(serverId)] = enc }
        Unit
    }

    suspend fun get(serverId: String): String? = withContext(Dispatchers.IO) {
        val enc = context.apiKeyDataStore.data.first()[stringPreferencesKey(serverId)]
            ?: return@withContext null
        runCatching {
            val ct = Base64.decode(enc, Base64.NO_WRAP)
            String(aead.decrypt(ct, serverId.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        }.getOrNull()
    }

    suspend fun remove(serverId: String) = withContext(Dispatchers.IO) {
        context.apiKeyDataStore.edit { it.remove(stringPreferencesKey(serverId)) }
        Unit
    }
}
