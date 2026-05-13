package net.unraidcontrol.app.ui.screens.main

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
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.data.repository.ServerRepository
import net.unraidcontrol.app.data.repository.SettingsRepository
import net.unraidcontrol.app.data.repository.SnapshotState
import net.unraidcontrol.app.data.repository.UnraidRepository
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
}
