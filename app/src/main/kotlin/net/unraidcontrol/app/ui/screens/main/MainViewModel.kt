package net.unraidcontrol.app.ui.screens.main

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.unraidcontrol.app.data.local.DockerView
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.InstallState
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.data.model.UpdateInfo
import net.unraidcontrol.app.data.model.UpdateState
import net.unraidcontrol.app.data.repository.ServerRepository
import net.unraidcontrol.app.data.repository.SettingsRepository
import net.unraidcontrol.app.data.repository.SnapshotState
import net.unraidcontrol.app.data.repository.UnraidRepository
import net.unraidcontrol.app.data.update.InstallEvent
import net.unraidcontrol.app.data.update.InstallStatusReceiver
import net.unraidcontrol.app.data.update.UpdateInstaller
import net.unraidcontrol.app.data.update.UpdateRepository
import javax.inject.Inject

data class MainUi(
    val activeServer: Server?,
    val connectionMode: ConnectionMode,
    val snapshot: SnapshotState,
    val settings: AppSettings,
    val dockerView: DockerView,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val unraid: UnraidRepository,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val installer: UpdateInstaller,
) : ViewModel() {

    val ui: StateFlow<MainUi> = combine(
        servers.activeServer,
        servers.connectionMode,
        unraid.snapshotStream(2000L),
        settings.settings,
        settings.dockerView,
    ) { active, mode, snap, s, view -> MainUi(active, mode, snap, s, view) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUi(null, ConnectionMode.Local, SnapshotState.Loading, AppSettings(), DockerView.List),
        )

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    val dismissedUpdateTag: StateFlow<String?> = settings.dismissedUpdateTag
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // One-shot check on app launch.
        checkForUpdate()

        // Mirror PackageInstaller events into our install state flow.
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

    /**
     * Suspends until the snapshot fetch finishes — pulled by the pull-to-refresh
     * UI so the spinner stays animated for the actual duration of the call.
     * Caller is responsible for launching this on a UI-bound scope.
     */
    suspend fun refresh() {
        unraid.snapshotOnce()
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
