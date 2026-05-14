package net.unraidcontrol.app.ui.screens.main

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.unraidcontrol.app.data.local.DockerView
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.data.model.ArrayInfo
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.Container
import net.unraidcontrol.app.data.model.InstallState
import net.unraidcontrol.app.data.model.LiveMetrics
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.data.model.ServerInfo
import net.unraidcontrol.app.data.model.UpdateInfo
import net.unraidcontrol.app.data.model.UpdateState
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.data.repository.DomainState
import net.unraidcontrol.app.data.repository.ServerRepository
import net.unraidcontrol.app.data.repository.SettingsRepository
import net.unraidcontrol.app.data.repository.UnraidRepository
import net.unraidcontrol.app.data.update.InstallEvent
import net.unraidcontrol.app.data.update.InstallStatusReceiver
import net.unraidcontrol.app.data.update.UpdateInstaller
import net.unraidcontrol.app.data.update.UpdateRepository
import javax.inject.Inject

data class MainUi(
    val activeServer: Server?,
    val connectionMode: ConnectionMode,
    val settings: AppSettings,
    val dockerView: DockerView,
)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val unraid: UnraidRepository,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val installer: UpdateInstaller,
) : ViewModel() {

    // ── Server identity + settings (cheap, always on) ─────────────────

    val ui: StateFlow<MainUi> = combine(
        servers.activeServer,
        servers.connectionMode,
        settings.settings,
        settings.dockerView,
    ) { active, mode, s, view -> MainUi(active, mode, s, view) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUi(null, ConnectionMode.Local, AppSettings(), DockerView.List),
        )

    // ── UI-driven gates ───────────────────────────────────────────────

    private val _selectedTab = MutableStateFlow(MainTab.Overview)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()
    fun setSelectedTab(tab: MainTab) { _selectedTab.value = tab }

    private val _dockerSheetOpen = MutableStateFlow(false)
    fun setDockerSheetOpen(open: Boolean) { _dockerSheetOpen.value = open }

    private val _appVisible = MutableStateFlow(true)
    fun setAppVisible(visible: Boolean) { _appVisible.value = visible }

    // ── Per-domain polling state (gated, lifecycle-aware) ─────────────
    //
    // Each StateFlow polls only while its gate is true. When the gate
    // closes, the upstream Flow is dropped (emptyFlow), but stateIn's
    // cached last value remains visible — re-entering a tab shows the
    // last-known data instantly, while a fresh poll runs in the background.

    private fun overviewOnly(): Flow<Boolean> =
        combine(_selectedTab, _appVisible) { tab, visible -> visible && tab == MainTab.Overview }

    private fun overviewOr(tab: MainTab): Flow<Boolean> =
        combine(_selectedTab, _appVisible) { current, visible ->
            visible && (current == MainTab.Overview || current == tab)
        }

    private fun dockerGate(): Flow<Boolean> =
        combine(_selectedTab, _dockerSheetOpen, _appVisible) { tab, sheet, visible ->
            visible && (tab == MainTab.Overview || tab == MainTab.Docker || sheet)
        }

    private fun <T> gatedStream(
        gate: Flow<Boolean>,
        stream: Flow<DomainState<T>>,
    ): StateFlow<DomainState<T>> = gate
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) stream else emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DomainState.Loading)

    val infoState: StateFlow<DomainState<ServerInfo>> =
        gatedStream(overviewOnly(), unraid.infoStream())

    val metricsState: StateFlow<DomainState<LiveMetrics>> =
        gatedStream(overviewOnly(), unraid.metricsStream())

    val arrayState: StateFlow<DomainState<ArrayInfo>> =
        gatedStream(overviewOr(MainTab.Array), unraid.arrayStream())

    val dockerState: StateFlow<DomainState<List<Container>>> =
        gatedStream(dockerGate(), unraid.dockerStream())

    val vmsState: StateFlow<DomainState<List<Vm>>> =
        gatedStream(overviewOr(MainTab.Vms), unraid.vmsStream())

    // ── App-updater plumbing (unchanged) ──────────────────────────────

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    val dismissedUpdateTag: StateFlow<String?> = settings.dismissedUpdateTag
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        checkForUpdate()

        viewModelScope.launch {
            InstallStatusReceiver.events.collect { event ->
                _installState.value = when (event) {
                    is InstallEvent.UserConfirmShown -> InstallState.Installing
                    is InstallEvent.Success          -> InstallState.Idle
                    is InstallEvent.Failed           -> InstallState.Failed(event.message)
                }
            }
        }
    }

    fun checkForUpdate() = viewModelScope.launch {
        _updateState.value = UpdateState.Checking
        val include = settings.includePrereleases.first()
        _updateState.value = updates.check(include)
        settings.setLastUpdateCheck(System.currentTimeMillis())
    }

    fun dismissUpdate(tag: String) = viewModelScope.launch {
        settings.setDismissedUpdateTag(tag)
    }

    fun installUpdate(info: UpdateInfo) = viewModelScope.launch {
        _installState.value = InstallState.Downloading(0f)
        try {
            val apk = installer.download(info.downloadUrl) { progress ->
                _installState.value = InstallState.Downloading(progress)
            }
            _installState.value = InstallState.Installing
            try {
                installer.install(apk)
            } catch (e: UpdateInstaller.NeedsPermissionException) {
                _installState.value = InstallState.NeedsPermission(e.intent)
            }
        } catch (e: Exception) {
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

    fun toggleConnection() = viewModelScope.launch {
        servers.setConnectionMode(
            if (ui.value.connectionMode == ConnectionMode.Local) ConnectionMode.Remote else ConnectionMode.Local
        )
    }

    /** Pull-to-refresh: force a fetch of every domain right now. The
     *  active gated streams pick up the new values via their pollers
     *  on the next iteration; this call just shortens the wait. */
    suspend fun refresh() {
        unraid.refreshAll()
    }

    fun startArray() = viewModelScope.launch { unraid.startArray() }
    fun stopArray()  = viewModelScope.launch { unraid.stopArray() }

    fun startContainer(id: String)   = viewModelScope.launch { unraid.startContainer(id) }
    fun stopContainer(id: String)    = viewModelScope.launch { unraid.stopContainer(id) }
    fun restartContainer(id: String) = viewModelScope.launch { unraid.restartContainer(id) }
    fun pauseContainer(id: String)   = viewModelScope.launch { unraid.pauseContainer(id) }

    fun startVm(id: String)            = viewModelScope.launch { unraid.startVm(id) }
    fun stopVm(id: String, force: Boolean) = viewModelScope.launch { unraid.stopVm(id, force) }
    fun pauseVm(id: String)            = viewModelScope.launch { unraid.pauseVm(id) }
    fun resumeVm(id: String)           = viewModelScope.launch { unraid.resumeVm(id) }

    fun setDockerView(view: DockerView) = viewModelScope.launch { settings.setDockerView(view) }

    suspend fun containerLogs(id: String): List<net.unraidcontrol.app.data.model.LogLine> =
        unraid.containerLogs(id)
}
