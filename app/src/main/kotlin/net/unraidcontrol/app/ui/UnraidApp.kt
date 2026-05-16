package net.unraidcontrol.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.data.repository.SettingsRepository
import net.unraidcontrol.app.ui.nav.AppNavGraph
import net.unraidcontrol.app.ui.theme.UnraidTheme
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    repo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
}

@Composable
fun UnraidApp(theme: ThemeViewModel = hiltViewModel()) {
    val settings by theme.settings.collectAsState()
    UnraidTheme(
        accent = Color(settings.accentHex.toInt()),
        themeMode = settings.themeMode,
        density = settings.density,
    ) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            AppNavGraph()
        }
    }
}
