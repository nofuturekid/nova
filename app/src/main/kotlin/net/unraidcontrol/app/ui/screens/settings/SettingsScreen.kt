package net.unraidcontrol.app.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.unraidcontrol.app.BuildConfig
import net.unraidcontrol.app.data.local.LayoutMode
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.data.model.InstallState
import net.unraidcontrol.app.data.model.UpdateInfo
import net.unraidcontrol.app.data.model.UpdateState
import net.unraidcontrol.app.data.repository.SettingsRepository
import net.unraidcontrol.app.data.update.InstallEvent
import net.unraidcontrol.app.data.update.InstallStatusReceiver
import net.unraidcontrol.app.data.update.UpdateInstaller
import net.unraidcontrol.app.data.update.UpdateRepository
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.SectionLabel
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.components.UnraidCard
import net.unraidcontrol.app.ui.components.UnraidIconButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.unraidcontrol.app.ui.screens.update.UpdateDialog
import net.unraidcontrol.app.ui.theme.AccentSwatches
import net.unraidcontrol.app.ui.theme.Density
import net.unraidcontrol.app.ui.theme.ThemeMode
import net.unraidcontrol.app.ui.theme.UnraidTheme
import javax.inject.Inject

data class SettingsUi(
    val settings: AppSettings,
    val dockerView: LayoutMode,
    val vmsView: LayoutMode,
    val arrayView: LayoutMode,
    val includePrereleases: Boolean,
    val lastUpdateCheck: Long?,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val updates: UpdateRepository,
    private val installer: UpdateInstaller,
) : ViewModel() {
    private val layouts = combine(
        repo.dockerView, repo.vmsView, repo.arrayView,
    ) { d, v, a -> Triple(d, v, a) }

    val state: StateFlow<SettingsUi> = combine(
        repo.settings,
        layouts,
        repo.includePrereleases,
        repo.lastUpdateCheck,
    ) { s, l, pre, last -> SettingsUi(s, l.first, l.second, l.third, pre, last) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUi(
                AppSettings(), LayoutMode.List, LayoutMode.List, LayoutMode.List,
                includePrereleases = false, lastUpdateCheck = null,
            ),
        )

    private val _checkState = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val checkState: StateFlow<UpdateState> = _checkState.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    /** True while an install this ViewModel started is in flight.
     *  InstallStatusReceiver.events is process-global and also collected by
     *  MainViewModel; without this guard an install started from the other
     *  screen would drive our state too (ADR-0012). */
    private var ownsInstall = false

    init {
        // Refresh the snapshot when the user opens Settings so they see the
        // current update state instead of a stale 'Up to date'.
        checkNow()

        // Mirror PackageInstaller broadcasts into our install-state flow so
        // the dialog reacts to system confirm / success / failure.
        viewModelScope.launch {
            InstallStatusReceiver.events.collect { event ->
                if (!ownsInstall) return@collect
                _installState.value = when (event) {
                    is InstallEvent.UserConfirmShown -> InstallState.Installing
                    is InstallEvent.Success          -> { ownsInstall = false; InstallState.Idle }
                    is InstallEvent.Failed           -> { ownsInstall = false; InstallState.Failed(event.message) }
                }
            }
        }
    }

    fun setAccent(hex: Long)            = viewModelScope.launch { repo.setAccent(hex) }
    fun setThemeMode(m: ThemeMode)      = viewModelScope.launch { repo.setThemeMode(m) }
    fun setDensity(d: Density)          = viewModelScope.launch { repo.setDensity(d) }
    fun setDockerView(v: LayoutMode)    = viewModelScope.launch { repo.setDockerView(v) }
    fun setVmsView(v: LayoutMode)       = viewModelScope.launch { repo.setVmsView(v) }
    fun setArrayView(v: LayoutMode)     = viewModelScope.launch { repo.setArrayView(v) }
    fun setIncludePrereleases(value: Boolean) = viewModelScope.launch {
        repo.setIncludePrereleases(value)
        // Reset dismissal so a previously-hidden update becomes visible again.
        repo.setDismissedUpdateTag(null)
        // Re-check immediately so the user sees the right answer for the new toggle.
        checkNow()
    }

    fun checkNow() = viewModelScope.launch {
        _checkState.value = UpdateState.Checking
        _checkState.value = updates.check(state.value.includePrereleases)
        repo.setLastUpdateCheck(System.currentTimeMillis())
    }

    fun installUpdate(info: UpdateInfo) = viewModelScope.launch {
        ownsInstall = true
        _installState.value = InstallState.Downloading(0f)
        try {
            val apk = installer.download(info.downloadUrl) { progress ->
                _installState.value = InstallState.Downloading(progress)
            }
            _installState.value = InstallState.Installing
            try {
                installer.install(apk)
            } catch (e: UpdateInstaller.NeedsPermissionException) {
                // No session committed → no broadcast will arrive.
                ownsInstall = false
                _installState.value = InstallState.NeedsPermission(e.intent)
            }
        } catch (e: Exception) {
            ownsInstall = false
            _installState.value = InstallState.Failed(e.message ?: "Update failed")
        }
    }

    fun resetInstall() {
        _installState.value = InstallState.Idle
    }

    fun launchPermissionIntent(state: InstallState.NeedsPermission, launch: (Intent) -> Unit) {
        launch(state.intent)
        _installState.value = InstallState.Idle
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val t = UnraidTheme.colors
    val ui by vm.state.collectAsState()
    val checkState by vm.checkState.collectAsState()
    val installState by vm.installState.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { /* user returns from system settings; they'll re-tap Install */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UnraidIconButton(icon = { UC.ChevL(20.dp, t.text) }, onClick = onBack)
            Text("Settings", color = t.text, style = MaterialTheme.typography.titleLarge)
        }

        val d = UnraidTheme.tokens
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenPad, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(d.gap),
        ) {
            SectionLabel("Theme")
            UnraidCard(padding = UnraidTheme.tokens.pad) {
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
                    SettingRow(label = "Theme") {
                        Segmented(
                            value = ui.settings.themeMode,
                            options = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark),
                            label = { it.name },
                            onChange = vm::setThemeMode,
                        )
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
            UnraidCard(padding = UnraidTheme.tokens.pad) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingRow(label = "Docker view") {
                        Segmented(
                            value = ui.dockerView,
                            options = listOf(LayoutMode.List, LayoutMode.Grid, LayoutMode.Grouped),
                            label = { it.name },
                            onChange = vm::setDockerView,
                        )
                    }
                    SettingRow(label = "VMs view") {
                        Segmented(
                            value = ui.vmsView,
                            options = listOf(LayoutMode.List, LayoutMode.Grid, LayoutMode.Grouped),
                            label = { it.name },
                            onChange = vm::setVmsView,
                        )
                    }
                    SettingRow(label = "Array view") {
                        Segmented(
                            value = ui.arrayView,
                            options = listOf(LayoutMode.List, LayoutMode.Grid, LayoutMode.Grouped),
                            label = { it.name },
                            onChange = vm::setArrayView,
                        )
                    }
                }
            }

            SectionLabel("Updates")
            UnraidCard(padding = UnraidTheme.tokens.pad) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingRow(label = "Installed version") {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            color = t.text,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    val cs = checkState
                    SettingRow(label = "Latest available") {
                        when (cs) {
                            is UpdateState.Checking ->
                                Text("Checking…", color = t.muted, style = MaterialTheme.typography.bodyMedium)
                            is UpdateState.UpToDate ->
                                Text("Up to date", color = t.accent, style = MaterialTheme.typography.labelLarge)
                            is UpdateState.Available ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("v${cs.info.version}", color = t.text, style = MaterialTheme.typography.labelLarge)
                                    if (cs.info.isPrerelease) Pill("BETA", tone = Tone.Warn)
                                    cs.info.publishedAtEpochMs?.let {
                                        Text(
                                            "· ${formatRelativeAge(it)}",
                                            color = t.muted,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            is UpdateState.Error ->
                                Text("Couldn't check", color = t.danger, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    SettingRow(label = "Last check") {
                        Text(
                            text = formatLastCheck(ui.lastUpdateCheck),
                            color = t.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    SettingRow(label = "Include pre-releases") {
                        Toggle(value = ui.includePrereleases, onChange = vm::setIncludePrereleases)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        UnraidButton(
                            onClick = { vm.checkNow() },
                            label = "Check now",
                            variant = BtnVariant.Tonal,
                            leadingIcon = { UC.Refresh(14.dp, t.accent) },
                        )
                        if (cs is UpdateState.Available) {
                            UnraidButton(
                                onClick = { showUpdateDialog = true },
                                label = "Install v${cs.info.version}",
                                variant = BtnVariant.Filled,
                            )
                        }
                    }
                }
            }
        }
    }

    val cs2 = checkState
    if (showUpdateDialog && cs2 is UpdateState.Available) {
        UpdateDialog(
            info = cs2.info,
            install = installState,
            onInstall = { vm.installUpdate(cs2.info) },
            onDismiss = {
                showUpdateDialog = false
                if (installState is InstallState.Failed) vm.resetInstall()
            },
            onGrantPermission = { state ->
                vm.launchPermissionIntent(state) { permissionLauncher.launch(it) }
            },
        )
    }
}

private fun formatLastCheck(epochMs: Long?): String =
    epochMs?.let { formatRelativeAge(it) } ?: "Never"

private fun formatRelativeAge(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    if (diff < 0) return "Just now"
    val minutes = diff / 60_000
    return when {
        minutes < 1    -> "Just now"
        minutes < 60   -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else           -> "${minutes / 1440}d ago"
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    val t = UnraidTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
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
            // Off-track uses t.text alpha so the contrast inverts with the theme:
            // light-grey dot on dark surface, dark-grey dot on light surface.
            .background(if (value) t.accent else t.text.copy(alpha = 0.2f))
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
            .background(t.muted.copy(alpha = 0.10f))
            .padding(2.dp),
    ) {
        options.forEach { opt ->
            val selected = opt == value
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) t.accentDim else Color.Transparent)
                    .clickable { onChange(opt) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label(opt),
                    color = if (selected) t.accent else t.muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
