package net.unraidcontrol.app.data.repository

import kotlinx.coroutines.flow.Flow
import net.unraidcontrol.app.data.local.LayoutMode
import net.unraidcontrol.app.data.local.SettingsStore
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.ui.theme.Density
import net.unraidcontrol.app.ui.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: SettingsStore,
) {
    val settings: Flow<AppSettings> = store.settings
    val dockerView: Flow<LayoutMode> = store.dockerView
    val vmsView: Flow<LayoutMode> = store.vmsView
    val arrayView: Flow<LayoutMode> = store.arrayView
    val includePrereleases: Flow<Boolean> = store.includePrereleases
    val lastUpdateCheck: Flow<Long?> = store.lastUpdateCheck
    val dismissedUpdateTag: Flow<String?> = store.dismissedUpdateTag
    val renameBannerDismissed: Flow<Boolean> = store.renameBannerDismissed

    suspend fun setAccent(hex: Long)         = store.setAccent(hex)
    suspend fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)
    suspend fun setDensity(density: Density) = store.setDensity(density)
    suspend fun setDockerView(view: LayoutMode) = store.setDockerView(view)
    suspend fun setVmsView(view: LayoutMode) = store.setVmsView(view)
    suspend fun setArrayView(view: LayoutMode) = store.setArrayView(view)
    suspend fun setIncludePrereleases(value: Boolean) = store.setIncludePrereleases(value)
    suspend fun setLastUpdateCheck(epochMs: Long) = store.setLastUpdateCheck(epochMs)
    suspend fun setDismissedUpdateTag(tag: String?) = store.setDismissedUpdateTag(tag)
    suspend fun setRenameBannerDismissed(value: Boolean) = store.setRenameBannerDismissed(value)
}
