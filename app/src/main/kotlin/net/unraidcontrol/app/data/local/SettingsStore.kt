package net.unraidcontrol.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.ui.theme.Density
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("unraid_prefs")

private object Keys {
    val Servers       = stringPreferencesKey("servers_json")
    val ActiveServer  = stringPreferencesKey("active_server_id")
    val ConnMode      = stringPreferencesKey("connection_mode")
    val AccentHex     = longPreferencesKey("accent_hex")
    val IsDark        = booleanPreferencesKey("is_dark")
    val Density       = stringPreferencesKey("density")
    val DockerView    = stringPreferencesKey("docker_view")
}

enum class DockerView { List, Grid, Grouped }

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    val servers: Flow<List<Server>> = ds.data.map { prefs ->
        prefs[Keys.Servers]?.let {
            runCatching { json.decodeFromString<List<Server>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    val activeServerId: Flow<String?> = ds.data.map { it[Keys.ActiveServer] }

    val connectionMode: Flow<ConnectionMode> = ds.data.map {
        when (it[Keys.ConnMode]) {
            "Remote" -> ConnectionMode.Remote
            else     -> ConnectionMode.Local
        }
    }

    val dockerView: Flow<DockerView> = ds.data.map {
        when (it[Keys.DockerView]) {
            "Grid"    -> DockerView.Grid
            "Grouped" -> DockerView.Grouped
            else      -> DockerView.List
        }
    }

    val settings: Flow<AppSettings> = ds.data.map { prefs ->
        AppSettings(
            accentHex = prefs[Keys.AccentHex] ?: 0xFF22D3A4,
            isDark    = prefs[Keys.IsDark] ?: true,
            density   = when (prefs[Keys.Density]) {
                "Compact"  -> Density.Compact
                "Spacious" -> Density.Spacious
                else       -> Density.Balanced
            },
        )
    }

    suspend fun setServers(list: List<Server>) {
        ds.edit { it[Keys.Servers] = json.encodeToString(list) }
    }

    suspend fun setActiveServer(id: String?) {
        ds.edit { prefs ->
            if (id == null) prefs.remove(Keys.ActiveServer) else prefs[Keys.ActiveServer] = id
        }
    }

    suspend fun setConnectionMode(mode: ConnectionMode) {
        ds.edit { it[Keys.ConnMode] = mode.name }
    }

    suspend fun setDockerView(view: DockerView) {
        ds.edit { it[Keys.DockerView] = view.name }
    }

    suspend fun setAccent(hex: Long) {
        ds.edit { it[Keys.AccentHex] = hex }
    }

    suspend fun setIsDark(dark: Boolean) {
        ds.edit { it[Keys.IsDark] = dark }
    }

    suspend fun setDensity(density: Density) {
        ds.edit { it[Keys.Density] = density.name }
    }
}
