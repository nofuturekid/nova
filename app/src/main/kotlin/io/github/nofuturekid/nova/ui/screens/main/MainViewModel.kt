package io.github.nofuturekid.nova.ui.screens.main

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.nofuturekid.nova.BuildConfig
import io.github.nofuturekid.nova.data.local.LayoutMode
import io.github.nofuturekid.nova.data.model.AppSettings
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.NotifType
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.InstallState
import io.github.nofuturekid.nova.data.model.LiveMetrics
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.ServerInfo
import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.UpdateInfo
import io.github.nofuturekid.nova.data.model.UpdateState
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.ServerRepository
import io.github.nofuturekid.nova.data.repository.SettingsRepository
import io.github.nofuturekid.nova.data.repository.UnraidRepository
import io.github.nofuturekid.nova.data.update.UpdateController
import io.github.nofuturekid.nova.data.update.UpdateRepository
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

    // ── Resilient action seam (triage #19, ADR-0037) ──────────────────
    //
    // Every user-initiated mutation (notification archive/delete, docker /
    // VM / array / parity start-stop, …) used to be a bare
    // `viewModelScope.launch { unraid.<action>() }` with NO try/catch and
    // NO global handler. A failing action over a flaky link (network drop,
    // GraphQL error) was an uncaught throwable in viewModelScope — it could
    // crash the whole app on a routine tap, with zero user feedback.
    //
    // Fix: route ALL such launchers through the single [launchAction] seam
    // below. On any Throwable it (a) re-throws CancellationException so
    // structured-concurrency cancellation is never swallowed, and (b) for
    // any other failure surfaces ONE transient, non-fatal message via
    // [userMessages] and returns — it never propagates to crash. The
    // established action→nudge/refetch convergence (UnraidRepository) still
    // reconciles the UI to server truth on the next poll, so the user's
    // intent is not silently lost — only the crash is.

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Transient, non-fatal action-failure messages for the UI to show in
     *  a snackbar. Lossless `tryEmit` via extraBufferCapacity; replay 0 so
     *  a returning subscriber doesn't re-show a stale error. */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /**
     * Defense-in-depth safety net (ADR-0037 §3). [launchAction] already
     * handles the expected failure path; this catches anything that still
     * slips through (a throwing launcher that bypassed the seam, a bug in
     * the seam itself) so it can never tear down the process. It does NOT
     * replace [launchAction] — a handled action still gets a user signal.
     */
    private val actionExceptionHandler = CoroutineExceptionHandler { _, e ->
        if (e is CancellationException) return@CoroutineExceptionHandler
        _userMessages.tryEmit(ACTION_FAILED_MESSAGE)
    }

    /**
     * Run a throwing repository action without ever crashing the app.
     * CancellationException is re-thrown (structured concurrency must keep
     * working — e.g. the scope being cleared). Any other Throwable is
     * caught: a brief [message] is emitted and the coroutine ends normally.
     * The repository's post-action nudge/refetch still converges the UI to
     * server truth on the next poll. [before]/[finally] mirror the prior
     * call sites' pre/post bookkeeping (e.g. the updating-pill set).
     */
    private fun launchAction(
        message: String = ACTION_FAILED_MESSAGE,
        before: () -> Unit = {},
        finally: () -> Unit = {},
        action: suspend () -> Unit,
    ): Job {
        before()
        return viewModelScope.launch(actionExceptionHandler) {
            runResilientAction(
                message = message,
                emit = { _userMessages.tryEmit(it) },
                onFinally = finally,
                action = action,
            )
        }
    }

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

    // Triage #3: the previous server's last Content survives in stateIn's
    // cache (and in any in-flight poll) across an active-server switch. We
    // must never show server A's data under server B's name. The cold
    // domainStream can't emit Loading on switch without also flashing a
    // skeleton on every same-server tab toggle — the screen always collects
    // every *State, so the gate flip alone re-collects the cold flow on each
    // tab change (no stateIn timeout is involved), and an extra Loading there
    // would regress ADR-0017's no-skeleton-flash-on-re-entry. So we reset on
    // SERVER IDENTITY here instead: any Content tagged with a serverBaseUrl
    // that isn't the current active server's URL is replaced with Loading
    // until a matching Content arrives. A same-server tab switch keeps the
    // cached Content (its serverBaseUrl still matches) — no new flash.
    private fun <T> gatedStream(
        gate: Flow<Boolean>,
        stream: Flow<DomainState<T>>,
    ): StateFlow<DomainState<T>> = combine(
        gate
            .distinctUntilChanged()
            .flatMapLatest { active -> if (active) stream else emptyFlow() },
        unraid.activeBaseUrl,
    ) { state, activeUrl -> resetIfForeignServer(state, activeUrl) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DomainState.Loading)

    val infoState: StateFlow<DomainState<ServerInfo>> =
        gatedStream(overviewOnly(), unraid.infoStream())

    val metricsState: StateFlow<DomainState<LiveMetrics>> =
        gatedStream(overviewOnly(), unraid.metricsStream())

    val networkThroughputState: StateFlow<DomainState<NetworkThroughput>> =
        gatedStream(overviewOnly(), unraid.networkThroughputStream())

    val temperatureState: StateFlow<DomainState<Temperature>> =
        gatedStream(overviewOnly(), unraid.temperatureStream())

    val arrayState: StateFlow<DomainState<ArrayInfo>> =
        gatedStream(overviewOr(MainTab.Array), unraid.arrayStream())

    val dockerState: StateFlow<DomainState<List<Container>>> =
        gatedStream(dockerGate(), unraid.dockerStream())

    val dockerStatsState: StateFlow<DomainState<Map<String, ContainerLiveStats>>> =
        gatedStream(dockerGate(), unraid.dockerStatsStream())

    /** The live per-container overlay, unwrapped to a plain map for the Docker UI
     *  (empty unless dockerStatsState is Content). DockerContent overlays this
     *  onto the polled container list via [joinContainerStats]. Kept SEPARATE from
     *  [dockerState] so the poll transport stays byte-identical (spec §C). */
    val dockerLiveStats: StateFlow<Map<String, ContainerLiveStats>> =
        dockerStatsState
            .map { (it as? DomainState.Content)?.value.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

    // ── Certificate trust (TOFU, ADR-0041) ───────────────────────────

    val certPrompt: StateFlow<UnraidRepository.CertPrompt?> =
        unraid.certPrompt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun trustCertificate(serverId: String, sha256: String) = viewModelScope.launch {
        unraid.trustLocalCertificate(serverId, sha256)
    }

    fun dismissCertPrompt() = unraid.dismissCertPrompt()

    init {
        if (BuildConfig.HAS_UPDATER) {
            checkForUpdate()
        }

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
                        status != io.github.nofuturekid.nova.data.model.ContainerUpdateStatus.UpToDate
                    }.toSet()
                }
            }
        }
    }

    fun checkForUpdate() = viewModelScope.launch {
        if (!BuildConfig.HAS_UPDATER) return@launch
        _updateState.value = UpdateState.Checking
        val include = settings.includePrereleases.first()
        _updateState.value = updates.check(include)
        settings.setLastUpdateCheck(System.currentTimeMillis())
    }

    fun dismissUpdate(tag: String) = viewModelScope.launch {
        settings.setDismissedUpdateTag(tag)
    }

    fun installUpdate(info: UpdateInfo) {
        if (!BuildConfig.HAS_UPDATER) return
        updateController.installUpdate(info)
    }

    fun resetInstall() {
        if (!BuildConfig.HAS_UPDATER) return
        updateController.resetInstall()
    }

    fun launchPermissionIntent(state: InstallState.NeedsPermission, launch: (Intent) -> Unit) {
        if (!BuildConfig.HAS_UPDATER) return
        try {
            launch(state.intent)
            updateController.resetInstall()
        } catch (e: android.content.ActivityNotFoundException) {
            // ROM without the unknown-app-sources settings activity: don't
            // silently reset to Idle (user gets no feedback). Surface it via
            // the existing install-error path.
            updateController.installFailed(
                "Can't open the install-permission screen on this device"
            )
        }
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

    fun startArray() = launchAction { unraid.startArray() }
    fun stopArray()  = launchAction { unraid.stopArray() }

    fun startContainer(id: String)   = launchAction { unraid.startContainer(id) }
    fun stopContainer(id: String)    = launchAction { unraid.stopContainer(id) }
    fun restartContainer(id: String) = launchAction { unraid.restartContainer(id) }
    fun pauseContainer(id: String)   = launchAction { unraid.pauseContainer(id) }

    fun updateContainer(id: String) {
        launchAction(
            before = { _updatingContainerIds.update { it + id } },
            finally = { _updatingContainerIds.update { it - id } },
        ) {
            unraid.updateContainer(id)
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
                it.updateStatus == io.github.nofuturekid.nova.data.model.ContainerUpdateStatus.UpdateAvailable ||
                    it.updateStatus == io.github.nofuturekid.nova.data.model.ContainerUpdateStatus.RebuildReady
            }
            ?.map { it.id }
            ?: return
        if (ids.isEmpty()) return
        launchAction(
            before = { _updatingContainerIds.update { it + ids } },
            finally = { _updatingContainerIds.update { it - ids.toSet() } },
        ) {
            unraid.updateAllContainers()
        }
    }

    fun startVm(id: String)            = launchAction { unraid.startVm(id) }
    fun stopVm(id: String, force: Boolean) = launchAction { unraid.stopVm(id, force) }
    fun pauseVm(id: String)            = launchAction { unraid.pauseVm(id) }
    fun resumeVm(id: String)           = launchAction { unraid.resumeVm(id) }
    fun rebootVm(id: String)           = launchAction { unraid.rebootVm(id) }
    fun resetVm(id: String)            = launchAction { unraid.resetVm(id) }

    // ── Notification actions ──────────────────────────────────────────
    // The repository nudges the notifications stream after each action, so
    // notificationsState (and the bell badge derived from it) refreshes
    // automatically — no local state mutation needed here.
    fun archiveNotification(id: String) = launchAction { unraid.archiveNotification(id) }
    fun unreadNotification(id: String)  = launchAction { unraid.unreadNotification(id) }
    fun deleteNotification(id: String, type: NotifType) =
        launchAction { unraid.deleteNotification(id, type) }
    fun archiveAllNotifications()       = launchAction { unraid.archiveAllNotifications() }
    fun deleteAllArchivedNotifications() = launchAction { unraid.deleteArchivedNotifications() }

    fun startParityCheck(correct: Boolean) = launchAction { unraid.startParityCheck(correct) }
    fun pauseParityCheck()  = launchAction { unraid.pauseParityCheck() }
    fun resumeParityCheck() = launchAction { unraid.resumeParityCheck() }
    fun cancelParityCheck() = launchAction { unraid.cancelParityCheck() }

    fun setDockerView(view: LayoutMode) = viewModelScope.launch { settings.setDockerView(view) }
    fun setVmsView(view: LayoutMode)    = viewModelScope.launch { settings.setVmsView(view) }
    fun setArrayView(view: LayoutMode)  = viewModelScope.launch { settings.setArrayView(view) }

    suspend fun containerLogs(id: String): List<io.github.nofuturekid.nova.data.model.LogLine> =
        unraid.containerLogs(id)

    internal companion object {
        /** The single transient signal a failed user action surfaces.
         *  Deliberately generic — the repository's next poll will reconcile
         *  the actual state; this just tells the user the tap didn't take. */
        const val ACTION_FAILED_MESSAGE = "Couldn't reach the server — try again"

        /**
         * The resilient-action control flow, extracted as a pure suspend
         * function so the production [launchAction] and the triage-#19 unit
         * test drive the *same* logic (no stale copy) — same-module
         * `internal` test-seam convention here (cf.
         * [io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.runNotificationActionFlow],
         * `pollDomainForTest`, `UpdateRepository.parseVersion`).
         *
         * Contract (triage #19, ADR-0037 — the FIXED intent):
         *  - [action] runs; on success nothing else happens (the
         *    repository's own post-action nudge/refetch still converges the
         *    UI on the next poll — that path is unchanged);
         *  - a [CancellationException] is RE-THROWN unchanged so structured
         *    concurrency (scope clear, parent cancel) keeps working;
         *  - ANY other [Throwable] is CAUGHT — it does NOT propagate (so it
         *    can never crash viewModelScope / the app) — and exactly one
         *    transient [message] is [emit]ted for the snackbar;
         *  - [onFinally] always runs (success, failure, or cancellation) so
         *    pre/post bookkeeping (the updating-pill set) is never stranded.
         */
        internal suspend fun runResilientAction(
            message: String = ACTION_FAILED_MESSAGE,
            emit: (String) -> Unit,
            onFinally: () -> Unit = {},
            action: suspend () -> Unit,
        ) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(message)
            } finally {
                onFinally()
            }
        }

        /**
         * Server-identity guard for [gatedStream] (triage #3). A [state]
         * that is [DomainState.Content] tagged with a [serverBaseUrl] other
         * than the currently [activeUrl] belongs to a server we just switched
         * away from — surface [DomainState.Loading] instead so server A's
         * data is never shown under server B's name. Non-Content states
         * ([NoServer]/[Error]/[Loading]) and Content already matching the
         * active server pass through unchanged (so a same-server tab switch
         * keeps its cached Content with no skeleton flash).
         */
        fun <T> resetIfForeignServer(
            state: DomainState<T>,
            activeUrl: String?,
        ): DomainState<T> =
            if (state is DomainState.Content<T> && state.serverBaseUrl != activeUrl) {
                DomainState.Loading
            } else {
                state
            }

        /**
         * Overlay live per-container [stats] onto the polled [containers] BY ID,
         * WITHOUT mutating [Container] (keeps the poll and WS transports decoupled,
         * spec §C). A LEFT join: every polled container is kept in its original order;
         * a container with no stats frame yet (or after a WS failure) gets `null`
         * stats so the row simply omits live numbers (no zero noise). The stats map's
         * own iteration order is irrelevant — output order is the polled list's order.
         * Called by DockerContent over [dockerLiveStats]; unit-tested in
         * ContainerStatsJoinTest.
         */
        fun joinContainerStats(
            containers: List<Container>,
            stats: Map<String, ContainerLiveStats>,
        ): List<Pair<Container, ContainerLiveStats?>> =
            containers.map { c -> c to stats[c.id] }
    }
}
