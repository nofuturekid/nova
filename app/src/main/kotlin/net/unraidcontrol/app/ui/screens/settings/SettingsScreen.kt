package net.unraidcontrol.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.unraidcontrol.app.data.local.DockerView
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.data.repository.SettingsRepository
import net.unraidcontrol.app.ui.components.SectionLabel
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidCard
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.theme.AccentSwatches
import net.unraidcontrol.app.ui.theme.Density
import net.unraidcontrol.app.ui.theme.UnraidTheme
import javax.inject.Inject

data class SettingsUi(
    val settings: AppSettings,
    val dockerView: DockerView,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<SettingsUi> = combine(repo.settings, repo.dockerView) { s, v -> SettingsUi(s, v) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi(AppSettings(), DockerView.List))

    fun setAccent(hex: Long)            = viewModelScope.launch { repo.setAccent(hex) }
    fun setDark(isDark: Boolean)        = viewModelScope.launch { repo.setDark(isDark) }
    fun setDensity(d: Density)          = viewModelScope.launch { repo.setDensity(d) }
    fun setDockerView(v: DockerView)    = viewModelScope.launch { repo.setDockerView(v) }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val t = UnraidTheme.colors
    val ui by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(t.bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UnraidIconButton(icon = { UC.ChevL(20.dp, t.text) }, onClick = onBack)
            Text("Settings", color = t.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Theme")
            UnraidCard(padding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingRow(label = "Accent") {
                        val currentArgb = ui.settings.accentHex.toInt()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccentSwatches.all.forEach { color ->
                                val swatchArgb = color.toArgb()
                                val selected = swatchArgb == currentArgb
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (selected) 2.dp else 0.dp,
                                            color = t.text,
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            // store as 0xAARRGGBB Long (sign-extended Int)
                                            vm.setAccent(swatchArgb.toLong() and 0xFFFFFFFFL)
                                        },
                                )
                            }
                        }
                    }
                    SettingRow(label = "Dark mode") {
                        Toggle(value = ui.settings.isDark, onChange = vm::setDark)
                    }
                    SettingRow(label = "Density") {
                        Segmented(
                            value = ui.settings.density,
                            options = listOf(Density.Compact, Density.Balanced, Density.Spacious),
                            label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                            onChange = vm::setDensity,
                        )
                    }
                }
            }

            SectionLabel("Layout")
            UnraidCard(padding = 14.dp) {
                SettingRow(label = "Docker view") {
                    Segmented(
                        value = ui.dockerView,
                        options = listOf(DockerView.List, DockerView.Grid, DockerView.Grouped),
                        label = { it.name },
                        onChange = vm::setDockerView,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    val t = UnraidTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun Toggle(value: Boolean, onChange: (Boolean) -> Unit) {
    val t = UnraidTheme.colors
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (value) t.accent else Color.White.copy(alpha = 0.15f))
            .clickable { onChange(!value) },
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .padding(horizontal = 3.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun <T> Segmented(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onChange: (T) -> Unit,
) {
    val t = UnraidTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(2.dp),
    ) {
        options.forEach { opt ->
            val selected = opt == value
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) t.surface else Color.Transparent)
                    .clickable { onChange(opt) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label(opt),
                    color = if (selected) t.text else t.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
