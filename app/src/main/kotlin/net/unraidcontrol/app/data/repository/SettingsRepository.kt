package net.unraidcontrol.app.data.repository

import kotlinx.coroutines.flow.Flow
import net.unraidcontrol.app.data.local.DockerView
import net.unraidcontrol.app.data.local.SettingsStore
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.ui.theme.Density
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: SettingsStore,
) {
    val settings: Flow<AppSettings> = store.settings
    val dockerView: Flow<DockerView> = store.dockerView

    suspend fun setAccent(hex: Long)         = store.setAccent(hex)
    suspend fun setDark(isDark: Boolean)     = store.setIsDark(isDark)
    suspend fun setDensity(density: Density) = store.setDensity(density)
    suspend fun setDockerView(view: DockerView) = store.setDockerView(view)
}
