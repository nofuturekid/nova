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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.unraidcontrol.app.data.local.LayoutMode
import net.unraidcontrol.app.data.model.AppSettings
import net.unraidcontrol.app.data.model.ArrayInfo
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.NotifType
import net.unraidcontrol.app.data.model.Container
import net.unraidcontrol.app.data.model.InstallState
import net.unraidcontrol.app.data.model.LiveMetrics
import net.unraidcontrol.app.data.model.Notifications
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.data.model.ServerInfo
import net.unraidcontrol.app.data.model.UpdateInfo
import net.unraidcontrol.app.data.model.UpdateState
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.data.repository.DomainState
import net.unraidcontrol.app.data.repository.ServerRepository
import net.unraidcontrol.app.data.repository.SettingsRepository
import net.unraidcontrol.app.data.repository.UnraidRepository
import net.unraidcontrol.app.data.update.UpdateController
import net.unraidcontrol.app.data.update.UpdateRepository
import javax.inject.Inject

data class MainUi(
    val activeServer: Server?,
    val connectionMode: ConnectionMode,
    val settings: AppSettings,
    val dockerView: LayoutMode,
    val vmsView: LayoutMode,
    val arrayView: LayoutMode,
)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val unraid: UnraidRepository,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val updateController: UpdateController,
) : ViewModel() {

    // ── Server identity + settings (cheap, always on) ─────────────────

    // kotlinx combine has typed overloads only up to 5 flows; fold the
    // three per-view layout flows into one Triple so the outer combine
    // stays at 4 args.
    private val layoutModes: kotlinx.coroutines.flow.Flow<Triple<LayoutMode, LayoutMode, LayoutMode>> =
        combine(settings.dockerView, settings.vmsView, settings.arrayView) { d, v, a -> Triple(d, v, a) }

    val ui: StateFlow<MainUi> = combine(
        servers.activeServer,
        servers.connectionMode,
        settings.settings,
        layoutModes,
    ) { active, mode, s, layouts ->
        MainUi(active, mode, s, layouts.first, layouts.second, layouts.third)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUi(
                null, ConnectionMode.Local, AppSettings(),
                LayoutMode.List, LayoutMode.List, LayoutMode.List,
            ),
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

    // Bell is global (not tab-bound) — gate only on app visibility.
    val notificationsState: StateFlow<DomainState<Notifications>> =
        gatedStream(_appVisible, unraid.notificationsStream())

    // ── In-flight container updates (ADR-0016) ────────────────────────
    //
    // The Update mutation runs server-side for 30s–several minutes (image
    // pull + recreate). We track which container IDs are currently being
    // updated so the UI can show an "Updating…" pill instead of the normal
    // action buttons. The set is cleared in `finally` so a failed or
    // cancelled mutation doesn't leave the UI stuck.
    private val _updatingContainerIds = MutableStateFlow<Set<String>>(emptySet())
    val updatingContainerIds: StateFlow<Set<String>> = _updatingContainerIds.asStateFlow()

    // ── App-updater plumbing (unchanged) ──────────────────────────────

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    // Install pipeline is owned by the @Singleton UpdateController
    // (ADR-0012 revisit / ADR-0030 D2). This ViewModel forwards the one
    // shared install state; the public API to the composables is
    // unchanged. There is intentionally no `ownsInstall` guard any more:
    // a single shared state means both screens reflect the one in-flight
    // install regardless of which screen started it.
    val installState: StateFlow<InstallState> = updateController.installState

    val dismissedUpdateTag: StateFlow<String?> = settings.dismissedUpdateTag
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        checkForUpdate()

        // Clear an id from `updatingContainerIds` as soon as the snapshot
        // poll says the container is explicitly UpToDate. The update mutation
        // can keep the HTTP connection open for the full duration of the
        // image pull + container recreate (minutes), so relying on the
        // `finally` block alone keeps the "Updating…" pill visible long
        // after the user-observable result is achieved. The snapshot is the
        // earlier observable signal — clear on that.
        viewModelScope.launch {
            dockerState.collect { state ->
                val containers = (state as? DomainState.Content<List<Container>>)?.value
                    ?: return@collect
                _updatingContainerIds.update { current ->
                    if (current.isEmpty()) return@update current
                    current.filter { id ->
                        // Drop only when the container is explicitly UpToDate.
                        // Unknown / not-found / still-hasUpdate → keep the
                        // pill visible.
                        val status = containers.firstOrNull { it.id == id }?.updateStatus
                        status != net.unraidcontrol.app.data.model.ContainerUpdateStatus.UpToDate
                    }.toSet()
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

    fun installUpdate(info: UpdateInfo) = updateController.installUpdate(info)

    fun resetInstall() = updateController.resetInstall()

    fun launchPermissionIntent(state: InstallState.NeedsPermission, launch: (Intent) -> Unit) {
        launch(state.intent)
        updateController.resetInstall()
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

    fun updateContainer(id: String) {
        _updatingContainerIds.update { it + id }
        viewModelScope.launch {
            try {
                unraid.updateContainer(id)
            } finally {
                _updatingContainerIds.update { it - id }
            }
        }
    }

    /** Bulk update: marks every container that currently reports
     *  hasUpdate() as updating and fires the server-side
     *  `updateAllContainers` mutation. The snapshot poll clears the
     *  pills individually as each container transitions to UpToDate
     *  (see the dockerState collector in init {}). */
    fun updateAllContainers() {
        val ids = (dockerState.value as? DomainState.Content<List<Container>>)
            ?.value
            ?.filter {
                it.updateStatus == net.unraidcontrol.app.data.model.ContainerUpdateStatus.UpdateAvailable ||
                    it.updateStatus == net.unraidcontrol.app.data.model.ContainerUpdateStatus.RebuildReady
            }
            ?.map { it.id }
            ?: return
        if (ids.isEmpty()) return
        _updatingContainerIds.update { it + ids }
        viewModelScope.launch {
            try {
                unraid.updateAllContainers()
            } finally {
                _updatingContainerIds.update { it - ids.toSet() }
            }
        }
    }

    fun startVm(id: String)            = viewModelScope.launch { unraid.startVm(id) }
    fun stopVm(id: String, force: Boolean) = viewModelScope.launch { unraid.stopVm(id, force) }
    fun pauseVm(id: String)            = viewModelScope.launch { unraid.pauseVm(id) }
    fun resumeVm(id: String)           = viewModelScope.launch { unraid.resumeVm(id) }
    fun rebootVm(id: String)           = viewModelScope.launch { unraid.rebootVm(id) }
    fun resetVm(id: String)            = viewModelScope.launch { unraid.resetVm(id) }

    // ── Notification actions ──────────────────────────────────────────
    // The repository nudges the notifications stream after each action, so
    // notificationsState (and the bell badge derived from it) refreshes
    // automatically — no local state mutation needed here.
    fun archiveNotification(id: String) = viewModelScope.launch { unraid.archiveNotification(id) }
    fun unreadNotification(id: String)  = viewModelScope.launch { unraid.unreadNotification(id) }
    fun deleteNotification(id: String, type: NotifType) =
        viewModelScope.launch { unraid.deleteNotification(id, type) }
    fun archiveAllNotifications()       = viewModelScope.launch { unraid.archiveAllNotifications() }

    fun startParityCheck(correct: Boolean) = viewModelScope.launch { unraid.startParityCheck(correct) }
    fun pauseParityCheck()  = viewModelScope.launch { unraid.pauseParityCheck() }
    fun resumeParityCheck() = viewModelScope.launch { unraid.resumeParityCheck() }
    fun cancelParityCheck() = viewModelScope.launch { unraid.cancelParityCheck() }

    fun setDockerView(view: LayoutMode) = viewModelScope.launch { settings.setDockerView(view) }
    fun setVmsView(view: LayoutMode)    = viewModelScope.launch { settings.setVmsView(view) }
    fun setArrayView(view: LayoutMode)  = viewModelScope.launch { settings.setArrayView(view) }

    suspend fun containerLogs(id: String): List<net.unraidcontrol.app.data.model.LogLine> =
        unraid.containerLogs(id)
}
