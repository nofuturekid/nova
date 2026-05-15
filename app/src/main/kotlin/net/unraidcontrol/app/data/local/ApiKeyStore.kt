package net.unraidcontrol.app.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** API keys are sensitive — keep them out of DataStore and in Tink-backed encrypted prefs. */
@Singleton
class ApiKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "unraid_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun put(serverId: String, key: String) {
        prefs.edit().putString(serverId, key).apply()
    }

    fun get(serverId: String): String? = prefs.getString(serverId, null)

    fun remove(serverId: String) {
        prefs.edit().remove(serverId).apply()
    }
}
