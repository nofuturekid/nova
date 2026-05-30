package io.github.nofuturekid.nova.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import io.github.nofuturekid.nova.BuildConfig
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.InstallState
import io.github.nofuturekid.nova.data.model.NotifType
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.UpdateState
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.model.hasUpdate
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.UnraidRepository
import io.github.nofuturekid.nova.ui.components.BtnVariant
import io.github.nofuturekid.nova.ui.components.ConfirmDialog
import io.github.nofuturekid.nova.ui.components.ConfirmRequest
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidButton
import io.github.nofuturekid.nova.ui.components.UnraidIconButton
import io.github.nofuturekid.nova.ui.screens.array.ArrayTab
import io.github.nofuturekid.nova.ui.screens.container.ContainerDetailSheet
import io.github.nofuturekid.nova.ui.screens.docker.DockerTab
import io.github.nofuturekid.nova.ui.screens.notifications.NotificationsSheet
import io.github.nofuturekid.nova.ui.screens.overview.OverviewTab
import io.github.nofuturekid.nova.ui.screens.update.UpdateBanner
import io.github.nofuturekid.nova.ui.screens.update.UpdateDialog
import io.github.nofuturekid.nova.ui.screens.vms.VmDetailSheet
import io.github.nofuturekid.nova.ui.screens.vms.VmsTab
import io.github.nofuturekid.nova.ui.theme.JetBrainsMono
import io.github.nofuturekid.nova.ui.theme.UnraidDims
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

enum class MainTab(val id: String, val label: String) {
    Overview("overview", "Overview"),
    Array("array", "Array"),
    Docker("docker", "Docker"),
    Vms("vms", "VMs"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenServerList: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    vm: MainViewModel = hiltViewModel(),
) {
    val t = UnraidTheme.colors
    val ui by vm.ui.collectAsState()
    val tab by vm.selectedTab.collectAsState()
    val infoState by vm.infoState.collectAsState()
    val metricsState by vm.metricsState.collectAsState()
    val networkState by vm.networkThroughputState.collectAsState()
    val temperatureState by vm.temperatureState.collectAsState()
    val dockerLiveStats by vm.dockerLiveStats.collectAsState()
    val arrayState by vm.arrayState.collectAsState()
    val dockerState by vm.dockerState.collectAsState()
    val vmsState by vm.vmsState.collectAsState()
    val notificationsState by vm.notificationsState.collectAsState()
    val displayThresholds by vm.displayThresholds.collectAsState()
    val scope = rememberCoroutineScope()

    // Pause polling when app is backgrounded (ADR-0017). Bound to the
    // hosting LifecycleOwner — when user navigates to Settings the polls
    // also pause, since this composable's lifecycle event sequence stops.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> vm.setAppVisible(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP  -> vm.setAppVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var openContainer by remember { mutableStateOf<Container?>(null) }
    // Set the Docker-poll gate synchronously with the state change. A
    // LaunchedEffect(openContainer) ran one frame late, briefly leaving
    // dockerGate() stale and needlessly cancelling/restarting the poll.
    val setOpenContainer: (Container?) -> Unit = {
        openContainer = it
        vm.setDockerSheetOpen(it != null)
    }
    var openVm by remember { mutableStateOf<Vm?>(null) }
    var showNotifications by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    val pullState = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }

    val updateState by vm.updateState.collectAsState()
    val installState by vm.installState.collectAsState()
    val dismissedTag by vm.dismissedUpdateTag.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { /* nothing — user returns; if granted, they tap Install again */ }

    // Transient action-failure signal (triage #19, ADR-0037). A failed
    // user action no longer crashes the app — the resilient launcher in
    // MainViewModel emits a brief message here; we surface it in the
    // existing Material 3 idiom (snackbar) and let the polling streams
    // reconcile the actual state.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.userMessages.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // TopBar's offline-pill reflects whatever the *current tab's* primary
        // domain stream is doing. We pick the most relevant per tab; Overview
        // bundles the worst of array/docker/vms.
        val topBarState: DomainState<*> = when (tab) {
            MainTab.Overview -> firstErrorOrAny(infoState, arrayState, dockerState, vmsState)
            MainTab.Array    -> arrayState
            MainTab.Docker   -> dockerState
            MainTab.Vms      -> vmsState
        }
        TopBar(
            server = ui.activeServer,
            connection = ui.connectionMode,
            state = topBarState,
            onOpenServerList = onOpenServerList,
            onMenu = onOpenSettings,
            onToggleConnection = { vm.toggleConnection() },
            notificationBadge = (notificationsState as? DomainState.Content)?.value?.badgeCount ?: 0,
            onOpenNotifications = { showNotifications = true },
        )

        if (BuildConfig.HAS_UPDATER) {
            val update = updateState
            if (update is UpdateState.Available && update.info.tag != dismissedTag) {
                UpdateBanner(
                    info = update.info,
                    onTap = { showUpdateDialog = true },
                    onDismiss = { vm.dismissUpdate(update.info.tag) },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            state = pullState,
            onRefresh = {
                if (refreshing) return@PullToRefreshBox
                refreshing = true
                scope.launch {
                    try {
                        vm.refresh()
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            AnimatedContent(targetState = tab, label = "tab") { current ->
                when (current) {
                    MainTab.Overview -> OverviewTab(
                        infoState = infoState,
                        metricsState = metricsState,
                        arrayState = arrayState,
                        dockerState = dockerState,
                        vmsState = vmsState,
                        server = ui.activeServer,
                        onAddServer = onAddServer,
                        networkThroughput = (networkState as? DomainState.Content)?.value,
                        temperature = (temperatureState as? DomainState.Content)?.value,
                        cpuWarnC = displayThresholds?.cpuWarnC,
                        cpuCritC = displayThresholds?.cpuCritC,
                    )
                    MainTab.Array    -> ArrayTab(
                        state = arrayState,
                        view = ui.arrayView,
                        onAddServer = onAddServer,
                        onStartArray = {
                            confirm = ConfirmRequest(
                                title = "Start the array?",
                                body = "All assigned disks will spin up and parity will become valid.",
                                confirmLabel = "Start array",
                                tone = Tone.Accent,
                                icon = { UC.Play(22.dp, t.accent) },
                                onConfirm = { vm.startArray(); confirm = null },
                            )
                        },
                        onStopArray = {
                            confirm = ConfirmRequest(
                                title = "Stop the array?",
                                body = "All disks will spin down. Make sure no services are actively using the array.",
                                confirmLabel = "Stop array",
                                tone = Tone.Danger,
                                icon = { UC.Power(22.dp, t.danger) },
                                onConfirm = { vm.stopArray(); confirm = null },
                            )
                        },
                        onStartParity = {
                            confirm = ConfirmRequest(
                                title = "Start a parity check?",
                                body = "Reads every disk to verify parity. Non-correcting (read-only). Can take hours on large arrays; the array stays usable but slower.",
                                confirmLabel = "Check parity",
                                tone = Tone.Accent,
                                icon = { UC.Shield(22.dp, t.accent) },
                                onConfirm = { vm.startParityCheck(correct = false); confirm = null },
                            )
                        },
                        onPauseParity = { vm.pauseParityCheck() },
                        onResumeParity = { vm.resumeParityCheck() },
                        onCancelParity = {
                            confirm = ConfirmRequest(
                                title = "Cancel the parity check?",
                                body = "Progress is discarded. You'll have to start over from the beginning next time.",
                                confirmLabel = "Cancel check",
                                tone = Tone.Danger,
                                icon = { UC.Stop(22.dp, t.danger) },
                                onConfirm = { vm.cancelParityCheck(); confirm = null },
                            )
                        },
                        globalDiskWarnC = displayThresholds?.diskWarnC,
                        globalDiskCritC = displayThresholds?.diskCritC,
                    )
                    MainTab.Docker   -> DockerTab(
                        state = dockerState,
                        view = ui.dockerView,
                        onAddServer = onAddServer,
                        stats = dockerLiveStats,
                        onOpenContainer = { setOpenContainer(it) },
                        onStart = { c -> vm.startContainer(c.id) },
                        onRestart = { c -> vm.restartContainer(c.id) },
                        onStop = { c ->
                            confirm = ConfirmRequest(
                                title = "Stop ${c.name}?",
                                body = "The container will be stopped immediately. Persistent data is preserved.",
                                confirmLabel = "Stop",
                                tone = Tone.Danger,
                                icon = { UC.Stop(22.dp, t.danger) },
                                onConfirm = { vm.stopContainer(c.id); confirm = null },
                            )
                        },
                        onUpdateAll = {
                            val n = (dockerState as? DomainState.Content<List<Container>>)
                                ?.value
                                ?.count { it.updateStatus.hasUpdate() }
                                ?: 0
                            confirm = ConfirmRequest(
                                title = if (n == 1) "Update 1 container?" else "Update $n containers?",
                                body = "Each will pull its latest image and recreate the container — they'll be unavailable during the update. The whole batch can take several minutes.",
                                confirmLabel = "Update all",
                                tone = Tone.Info,
                                icon = { UC.Refresh(22.dp, t.info) },
                                onConfirm = { vm.updateAllContainers(); confirm = null },
                            )
                        },
                    )
                    MainTab.Vms      -> VmsTab(
                        state = vmsState,
                        view = ui.vmsView,
                        onAddServer = onAddServer,
                        onStart  = { v -> vm.startVm(v.id) },
                        onResume = { v -> vm.resumeVm(v.id) },
                        onPause  = { v -> vm.pauseVm(v.id) },
                        onOpenVm = { openVm = it },
                    )
                }
            }
        }

        TabBar(active = tab, onChange = { vm.setSelectedTab(it) })
    }

    if (openContainer != null) {
        val dockerContent = dockerState as? DomainState.Content<List<Container>>
        // Show the LIVE container from the latest snapshot, not the
        // frozen one captured on tap. Otherwise the "Update" button keeps
        // showing (and just flickers as isUpdating toggles) after a
        // successful update, because the snapshot's updateStatus never
        // refreshes. Match by id, fall back to name in case a recreate
        // changed the id, snapshot as last resort.
        val shown = dockerContent?.value?.firstOrNull { it.id == openContainer!!.id }
            ?: dockerContent?.value?.firstOrNull { it.name == openContainer!!.name }
            ?: openContainer!!
        val baseUrl = dockerContent?.serverBaseUrl.orEmpty()
        val updatingIds by vm.updatingContainerIds.collectAsState()
        ContainerDetailSheet(
            container = shown,
            serverBaseUrl = baseUrl,
            // Reactive overlay: dockerLiveStats is collected above, so the open
            // sheet recomposes live as new dockerContainerStats frames arrive.
            liveStats = dockerLiveStats[shown.id],
            isUpdating = shown.id in updatingIds,
            onFetchLogs = { id -> vm.containerLogs(id) },
            onRefresh = { vm.refresh() },
            onDismiss = { setOpenContainer(null) },
            onStart   = { vm.startContainer(it.id); setOpenContainer(null) },
            onRestart = { vm.restartContainer(it.id) },
            onStop    = { c ->
                confirm = ConfirmRequest(
                    title = "Stop ${c.name}?",
                    body = "The container will be stopped immediately. Persistent data is preserved.",
                    confirmLabel = "Stop",
                    tone = Tone.Danger,
                    icon = { UC.Stop(22.dp, t.danger) },
                    onConfirm = { vm.stopContainer(c.id); confirm = null; setOpenContainer(null) },
                )
            },
            onUpdate  = { c -> vm.updateContainer(c.id) },
        )
    }

    if (openVm != null) {
        VmDetailSheet(
            vm = openVm!!,
            onDismiss = { openVm = null },
            onStart  = { vm.startVm(it.id); openVm = null },
            onResume = { vm.resumeVm(it.id); openVm = null },
            onPause  = { vm.pauseVm(it.id); openVm = null },
            onStop   = { v ->
                confirm = ConfirmRequest(
                    title = "Force stop ${v.name}?",
                    body = "This is equivalent to pulling the power cord. Unsaved data may be lost.",
                    confirmLabel = "Force stop",
                    tone = Tone.Danger,
                    icon = { UC.Power(22.dp, t.danger) },
                    onConfirm = { vm.stopVm(v.id, force = true); confirm = null; openVm = null },
                )
            },
            onReboot = { vm.rebootVm(it.id); openVm = null },
            onReset  = { v ->
                confirm = ConfirmRequest(
                    title = "Reset ${v.name}?",
                    body = "Hard reset — like the physical reset button. The VM restarts immediately without a clean shutdown; unsaved data may be lost.",
                    confirmLabel = "Reset",
                    tone = Tone.Danger,
                    icon = { UC.Power(22.dp, t.danger) },
                    onConfirm = { vm.resetVm(v.id); confirm = null; openVm = null },
                )
            },
        )
    }

    if (showNotifications) {
        NotificationsSheet(
            state = notificationsState,
            onDismiss = { showNotifications = false },
            onArchive = { vm.archiveNotification(it) },
            onUnread = { vm.unreadNotification(it) },
            onArchiveAll = { vm.archiveAllNotifications() },
            onDeleteAllArchivedRequest = {
                confirm = ConfirmRequest(
                    title = "Delete all archived?",
                    body = "All archived notifications will be permanently deleted. This cannot be undone.",
                    confirmLabel = "Delete all",
                    tone = Tone.Danger,
                    onConfirm = { vm.deleteAllArchivedNotifications(); confirm = null },
                )
            },
            onDeleteRequest = { n ->
                confirm = ConfirmRequest(
                    title = "Delete notification?",
                    body = "\"${n.title}\" will be permanently deleted.",
                    confirmLabel = "Delete",
                    tone = Tone.Danger,
                    onConfirm = { vm.deleteNotification(n.id, n.type ?: NotifType.Unread); confirm = null },
                )
            },
        )
    }

    ConfirmDialog(request = confirm, onDismiss = { confirm = null })

    val certPrompt by vm.certPrompt.collectAsState()
    certPrompt?.let { p ->
        val t = UnraidTheme.colors
        AlertDialog(
            onDismissRequest = { vm.dismissCertPrompt() },
            shape = RoundedCornerShape(UnraidDims.radDialog),
            containerColor = t.surface2,
            titleContentColor = t.text,
            textContentColor = t.muted,
            title = {
                Text(
                    text = if (p.previousSha256 == null) "Trust this certificate?" else "Certificate changed",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Column {
                    Text(
                        text = if (p.previousSha256 == null)
                            "${p.serverName} presented a self-signed certificate. Trust it for the local connection?"
                        else
                            "${p.serverName}'s certificate changed. Only trust this if you changed it yourself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (p.previousSha256 != null) {
                        Text(
                            text = "Was: ${p.previousSha256}",
                            style = MaterialTheme.typography.labelSmall,
                            color = t.muted,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = "SHA-256: ${p.presentedSha256}",
                        style = MaterialTheme.typography.labelSmall,
                        color = t.muted,
                    )
                }
            },
            confirmButton = {
                UnraidButton(
                    onClick = { vm.trustCertificate(p.serverId, p.presentedSha256) },
                    label = if (p.previousSha256 == null) "Trust" else "Trust new",
                    variant = BtnVariant.Text,
                    tone = Tone.Accent,
                )
            },
            dismissButton = {
                UnraidButton(
                    onClick = { vm.dismissCertPrompt() },
                    label = "Cancel",
                    variant = BtnVariant.Text,
                    tone = Tone.Neutral,
                )
            },
        )
    }

    if (BuildConfig.HAS_UPDATER) {
        val u = updateState
        if (showUpdateDialog && u is UpdateState.Available) {
            UpdateDialog(
                info = u.info,
                install = installState,
                onInstall = { vm.installUpdate(u.info) },
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = 72.dp),
        )
    }
}

/**
 * Compare a handful of domain states and prefer Error/NoServer over
 * Loading/Content. Used by [TopBar]'s offline-indicator on the Overview tab
 * (which would otherwise need to invent its own meta-state across five
 * separate domain streams).
 */
private fun firstErrorOrAny(vararg states: DomainState<*>): DomainState<*> {
    states.firstOrNull { it is DomainState.NoServer }?.let { return it }
    states.firstOrNull { it is DomainState.Error }?.let { return it }
    states.firstOrNull { it is DomainState.Content<*> }?.let { return it }
    return DomainState.Loading
}

@Composable
private fun TopBar(
    server: Server?,
    connection: ConnectionMode,
    state: DomainState<*>,
    onOpenServerList: () -> Unit,
    onMenu: () -> Unit,
    onToggleConnection: () -> Unit,
    notificationBadge: Int,
    onOpenNotifications: () -> Unit,
) {
    val t = UnraidTheme.colors
    val offline = state is DomainState.Error || state is DomainState.NoServer
    val connTone: Tone = when {
        offline -> Tone.Danger
        connection == ConnectionMode.Remote -> Tone.Info
        else -> Tone.Accent
    }
    val connLabel = when {
        state is DomainState.NoServer -> "No server"
        offline -> "Offline"
        connection == ConnectionMode.Remote -> "Remote"
        else -> "Local"
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        onClick = onOpenServerList,
                        role = Role.Button,
                        onClickLabel = "Switch server",
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                    )
                    .sizeIn(minHeight = UnraidDims.touchMin)
                    .padding(start = 6.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(t.accentDim),
                    contentAlignment = Alignment.Center,
                ) { UC.Server(18.dp, t.accent) }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = server?.name ?: "Pick a server",
                            color = t.text,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        UC.ChevD(14.dp, t.muted)
                    }
                    if (server != null) {
                        Text(
                            text = server.hostname,
                            color = t.muted,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        onClick = onToggleConnection,
                        role = Role.Button,
                        onClickLabel = "Switch connection, currently $connLabel",
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                    )
                    .sizeIn(minHeight = UnraidDims.touchMin),
                contentAlignment = Alignment.Center,
            ) {
                Pill(label = connLabel, tone = connTone, dot = true)
            }
            Box {
                UnraidIconButton(
                    icon = { UC.Bell(20.dp, t.text) },
                    onClick = onOpenNotifications,
                    contentDescription = if (notificationBadge > 0)
                        "Notifications, $notificationBadge unread"
                    else "Notifications",
                )
                if (notificationBadge > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(t.danger),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (notificationBadge > 9) "9+" else "$notificationBadge",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            UnraidIconButton(
                icon = { UC.Settings(20.dp, t.text) },
                onClick = onMenu,
                contentDescription = "Settings",
            )
        }
        HorizontalDivider(color = t.border, thickness = 1.dp)
    }
}

@Composable
private fun TabBar(active: MainTab, onChange: (MainTab) -> Unit) {
    val t = UnraidTheme.colors
    Column {
        HorizontalDivider(color = t.border, thickness = 1.dp)
        // M3 NavigationBar: built-in selected-state semantics ("tab, x of 4,
        // selected"), 48dp targets, ripple, active indicator. Parent Column
        // already consumes systemBars, so zero the bar's own insets to avoid
        // double bottom padding.
        NavigationBar(
            containerColor = t.bg,
            tonalElevation = 0.dp,
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        ) {
            val itemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = t.accent,
                selectedTextColor = t.text,
                indicatorColor = t.accentDim,
                unselectedIconColor = t.muted,
                unselectedTextColor = t.muted,
            )
            MainTab.values().forEach { tab ->
                NavigationBarItem(
                    selected = tab == active,
                    onClick = { onChange(tab) },
                    colors = itemColors,
                    icon = {
                        // UC.* pass tint=Color.Unspecified, which disables
                        // Icon tinting entirely (it does NOT fall back to
                        // LocalContentColor), so NavigationBarItem's icon
                        // colour never reaches them — they render near-black
                        // and vanish on the dark nav background. Pass the
                        // selected/unselected tint explicitly, mirroring
                        // itemColors.
                        val tint = if (tab == active) t.accent else t.muted
                        when (tab) {
                            MainTab.Overview -> UC.Dashboard(20.dp, tint)
                            MainTab.Array    -> UC.Disk(20.dp, tint)
                            MainTab.Docker   -> UC.Docker(20.dp, tint)
                            MainTab.Vms      -> UC.Vm(20.dp, tint)
                        }
                    },
                    label = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
    }
}
