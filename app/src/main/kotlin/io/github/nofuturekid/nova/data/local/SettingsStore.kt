package io.github.nofuturekid.nova.data.local

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.nofuturekid.nova.data.alerts.ServerAlertState
import io.github.nofuturekid.nova.data.model.AppSettings
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.ui.theme.Density
import io.github.nofuturekid.nova.ui.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("unraid_prefs")

private object Keys {
    val Servers             = stringPreferencesKey("servers_json")
    val ActiveServer        = stringPreferencesKey("active_server_id")
    val ConnMode            = stringPreferencesKey("connection_mode")
    val AccentHex           = longPreferencesKey("accent_hex")
    val ThemeMode           = stringPreferencesKey("theme_mode")
    val Density             = stringPreferencesKey("density")
    val DockerView          = stringPreferencesKey("docker_view")
    val VmsView             = stringPreferencesKey("vms_view")
    val ArrayView           = stringPreferencesKey("array_view")
    val IncludePrereleases  = booleanPreferencesKey("include_prereleases")
    val LastUpdateCheck     = longPreferencesKey("last_update_check")
    val DismissedUpdateTag  = stringPreferencesKey("dismissed_update_tag")
    val CertPins              = stringPreferencesKey("local_cert_pins_json")
    val AlertStates           = stringPreferencesKey("alert_state_json")
}

/** Shared layout mode for the Docker / VMs / Array list views. Each
 *  view persists its own choice; "Grouped" is interpreted per view
 *  (Docker → by status, VMs → by state, Array → by disk type).
 *  "Compose" exists for Docker only (grouped by Compose project); the
 *  VMs and Array settings never offer it and would show it as Grouped. */
enum class LayoutMode { List, Grid, Grouped, Compose }

/** One consistent read of the stored server selection; see [SettingsStore.serverSnapshot]. */
data class ServerSnapshot(
    val servers: List<Server>,
    val activeId: String?,
    val mode: ConnectionMode,
    val pins: Map<String, String>,
)

@Singleton
class SettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val ds = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    val servers: Flow<List<Server>> = ds.data.map { decodeServers(it) }

    private fun decodeServers(prefs: Preferences): List<Server> =
        prefs[Keys.Servers]?.let {
            runCatching { json.decodeFromString<List<Server>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()

    /**
     * The inputs the active server is derived from (list, active id, mode, pins), read
     * from ONE DataStore snapshot. Combining the separate flows above can pair the new
     * list with the pins of the previous snapshot, i.e. a new server without its pin,
     * even when both were written in one transaction (#292).
     */
    val serverSnapshot: Flow<ServerSnapshot> = ds.data.map {
        ServerSnapshot(decodeServers(it), it[Keys.ActiveServer], decodeMode(it), decodePins(it))
    }

    /** server id → pinned local-cert SHA-256 (colon-separated hex). */
    val certPins: Flow<Map<String, String>> = ds.data.map { decodePins(it) }

    private fun decodePins(prefs: Preferences): Map<String, String> =
        prefs[Keys.CertPins]?.let {
            runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()

    suspend fun pinFor(serverId: String): String? = certPins.first()[serverId]

    suspend fun setPin(serverId: String, sha256: String) {
        val next = certPins.first().toMutableMap().apply { put(serverId, sha256) }
        ds.edit { it[Keys.CertPins] = json.encodeToString(next) }
    }

    suspend fun clearPin(serverId: String) {
        val next = certPins.first().toMutableMap().apply { remove(serverId) }
        ds.edit { it[Keys.CertPins] = json.encodeToString(next) }
    }

    /**
     * The server list, the server's pin and (when none is set yet) the active
     * server in ONE DataStore transaction, so the flows built on them (the
     * polling streams) never see the new server without the pin the user
     * accepted for it. [pin] null with [dropPin] false keeps the stored pin.
     */
    suspend fun saveServer(list: List<Server>, serverId: String, pin: String?, dropPin: Boolean) {
        ds.edit { prefs ->
            val before = decodeServers(prefs)
            val activeId = prefs[Keys.ActiveServer]
            prefs[Keys.Servers] = json.encodeToString(list)
            val pins = decodePins(prefs).toMutableMap()
            when {
                dropPin -> pins.remove(serverId)
                pin != null -> pins[serverId] = pin
            }
            prefs[Keys.CertPins] = json.encodeToString(pins.toMap())
            // The first server becomes active. So does the first real server added while the demo
            // entry is the active one: the demo is a place to look around, not a server to stay on.
            val demoWasActive = before.any { it.id == activeId && it.isDemo }
            val addedRealServer = before.none { it.id == serverId } && list.any { it.id == serverId && !it.isDemo }
            if (activeId == null || (demoWasActive && addedRealServer)) prefs[Keys.ActiveServer] = serverId
        }
    }

    val activeServerId: Flow<String?> = ds.data.map { it[Keys.ActiveServer] }

    val connectionMode: Flow<ConnectionMode> = ds.data.map { decodeMode(it) }

    private fun decodeMode(prefs: Preferences): ConnectionMode =
        when (prefs[Keys.ConnMode]) {
            "Remote" -> ConnectionMode.Remote
            else     -> ConnectionMode.Local
        }

    private fun layoutFlow(key: androidx.datastore.preferences.core.Preferences.Key<String>): Flow<LayoutMode> =
        ds.data.map {
            when (it[key]) {
                "Grid"    -> LayoutMode.Grid
                "Grouped" -> LayoutMode.Grouped
                "Compose" -> LayoutMode.Compose
                else      -> LayoutMode.List
            }
        }

    val dockerView: Flow<LayoutMode> = layoutFlow(Keys.DockerView)
    val vmsView: Flow<LayoutMode>    = layoutFlow(Keys.VmsView)
    val arrayView: Flow<LayoutMode>  = layoutFlow(Keys.ArrayView)

    val settings: Flow<AppSettings> = ds.data.map { prefs ->
        AppSettings(
            accentHex = prefs[Keys.AccentHex] ?: 0xFF22D3A4,
            themeMode = when (prefs[Keys.ThemeMode]) {
                "Light" -> ThemeMode.Light
                "Dark"  -> ThemeMode.Dark
                else    -> ThemeMode.System
            },
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

    suspend fun setDockerView(view: LayoutMode) {
        ds.edit { it[Keys.DockerView] = view.name }
    }

    suspend fun setVmsView(view: LayoutMode) {
        ds.edit { it[Keys.VmsView] = view.name }
    }

    suspend fun setArrayView(view: LayoutMode) {
        ds.edit { it[Keys.ArrayView] = view.name }
    }

    suspend fun setAccent(hex: Long) {
        ds.edit { it[Keys.AccentHex] = hex }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        ds.edit { it[Keys.ThemeMode] = mode.name }
    }

    suspend fun setDensity(density: Density) {
        ds.edit { it[Keys.Density] = density.name }
    }

    // ── Updater state ─────────────────────────────────────────────

    val includePrereleases: Flow<Boolean> = ds.data.map { it[Keys.IncludePrereleases] ?: false }
    val lastUpdateCheck: Flow<Long?> = ds.data.map { it[Keys.LastUpdateCheck] }
    val dismissedUpdateTag: Flow<String?> = ds.data.map { it[Keys.DismissedUpdateTag] }

    suspend fun setIncludePrereleases(value: Boolean) {
        ds.edit { it[Keys.IncludePrereleases] = value }
    }

    suspend fun setLastUpdateCheck(epochMs: Long) {
        ds.edit { it[Keys.LastUpdateCheck] = epochMs }
    }

    suspend fun setDismissedUpdateTag(tag: String?) {
        ds.edit { prefs ->
            if (tag == null) prefs.remove(Keys.DismissedUpdateTag)
            else prefs[Keys.DismissedUpdateTag] = tag
        }
    }

    // ── Alert state ───────────────────────────────────────────────

    /** server id → what was already reported for it. Unreadable data counts as empty. */
    private fun decodeAlertStates(prefs: Preferences): Map<String, ServerAlertState> =
        prefs[Keys.AlertStates]?.let {
            runCatching { json.decodeFromString<Map<String, ServerAlertState>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()

    suspend fun alertStateFor(serverId: String): ServerAlertState =
        decodeAlertStates(ds.data.first())[serverId] ?: ServerAlertState()

    suspend fun setAlertState(serverId: String, state: ServerAlertState) {
        ds.edit { prefs ->
            prefs[Keys.AlertStates] = json.encodeToString(decodeAlertStates(prefs) + (serverId to state))
        }
    }

    suspend fun clearAlertState(serverId: String) {
        ds.edit { prefs ->
            val next = decodeAlertStates(prefs) - serverId
            if (next.isEmpty()) prefs.remove(Keys.AlertStates) else prefs[Keys.AlertStates] = json.encodeToString(next)
        }
    }

    suspend fun clearAllAlertStates() {
        ds.edit { it.remove(Keys.AlertStates) }
    }
}
