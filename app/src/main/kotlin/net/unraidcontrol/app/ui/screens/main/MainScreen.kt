package net.unraidcontrol.app.ui.screens.main

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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import net.unraidcontrol.app.data.model.ArrayState
import net.unraidcontrol.app.data.model.Container
import net.unraidcontrol.app.data.model.ConnectionMode
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.data.repository.SnapshotState
import net.unraidcontrol.app.ui.components.ConfirmDialog
import net.unraidcontrol.app.ui.components.ConfirmRequest
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.screens.array.ArrayTab
import net.unraidcontrol.app.ui.screens.container.ContainerDetailSheet
import net.unraidcontrol.app.ui.screens.docker.DockerTab
import net.unraidcontrol.app.ui.screens.overview.OverviewTab
import net.unraidcontrol.app.ui.screens.vms.VmsTab
import net.unraidcontrol.app.ui.theme.JetBrainsMono
import net.unraidcontrol.app.ui.theme.UnraidTheme

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
    onEditActiveServer: (Server) -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    vm: MainViewModel = hiltViewModel(),
) {
    val t = UnraidTheme.colors
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(MainTab.Overview) }
    var openContainer by remember { mutableStateOf<Container?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    val pullState = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        TopBar(
            server = ui.activeServer,
            connection = ui.connectionMode,
            snapshot = ui.snapshot,
            onOpenServerList = onOpenServerList,
            onMenu = {
                ui.activeServer?.let(onEditActiveServer) ?: onOpenSettings()
            },
            onToggleConnection = { vm.toggleConnection() },
        )

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
                    MainTab.Overview -> OverviewTab(ui.snapshot, ui.activeServer, onAddServer)
                    MainTab.Array    -> ArrayTab(
                        snapshot = ui.snapshot,
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
                    )
                    MainTab.Docker   -> DockerTab(
                        snapshot = ui.snapshot,
                        view = ui.dockerView,
                        onAddServer = onAddServer,
                        onOpenContainer = { openContainer = it },
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
                    )
                    MainTab.Vms      -> VmsTab(
                        snapshot = ui.snapshot,
                        onAddServer = onAddServer,
                        onStart  = { v -> vm.startVm(v.id) },
                        onResume = { v -> vm.resumeVm(v.id) },
                        onPause  = { v -> vm.pauseVm(v.id) },
                        onStop   = { v ->
                            confirm = ConfirmRequest(
                                title = "Force stop ${v.name}?",
                                body = "This is equivalent to pulling the power cord. Unsaved data may be lost.",
                                confirmLabel = "Force stop",
                                tone = Tone.Danger,
                                icon = { UC.Power(22.dp, t.danger) },
                                onConfirm = { vm.stopVm(v.id, force = true); confirm = null },
                            )
                        },
                    )
                }
            }
        }

        TabBar(active = tab, onChange = { tab = it })
    }

    if (openContainer != null) {
        val baseUrl = (ui.snapshot as? net.unraidcontrol.app.data.repository.SnapshotState.Content)
            ?.snapshot?.serverBaseUrl.orEmpty()
        ContainerDetailSheet(
            container = openContainer!!,
            serverBaseUrl = baseUrl,
            onDismiss = { openContainer = null },
            onStart   = { vm.startContainer(it.id); openContainer = null },
            onRestart = { vm.restartContainer(it.id) },
            onStop    = { c ->
                confirm = ConfirmRequest(
                    title = "Stop ${c.name}?",
                    body = "The container will be stopped immediately. Persistent data is preserved.",
                    confirmLabel = "Stop",
                    tone = Tone.Danger,
                    icon = { UC.Stop(22.dp, t.danger) },
                    onConfirm = { vm.stopContainer(c.id); confirm = null; openContainer = null },
                )
            },
        )
    }

    ConfirmDialog(request = confirm, onDismiss = { confirm = null })
}

@Composable
private fun TopBar(
    server: Server?,
    connection: ConnectionMode,
    snapshot: SnapshotState,
    onOpenServerList: () -> Unit,
    onMenu: () -> Unit,
    onToggleConnection: () -> Unit,
) {
    val t = UnraidTheme.colors
    val offline = snapshot is SnapshotState.Error || snapshot is SnapshotState.NoServer
    val connTone: Tone = when {
        offline -> Tone.Danger
        connection == ConnectionMode.Remote -> Tone.Info
        else -> Tone.Accent
    }
    val connLabel = when {
        snapshot is SnapshotState.NoServer -> "No server"
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
                    .clickable(onClick = onOpenServerList)
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
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        UC.ChevD(14.dp, t.muted)
                    }
                    if (server != null) {
                        Text(
                            text = server.hostname,
                            color = t.muted,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMono,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.clickable(onClick = onToggleConnection)) {
                Pill(label = connLabel, tone = connTone, dot = true)
            }
            UnraidIconButton(icon = { UC.Menu(20.dp, t.text) }, onClick = onMenu)
        }
        HorizontalDivider(color = t.border, thickness = 1.dp)
    }
}

@Composable
private fun TabBar(active: MainTab, onChange: (MainTab) -> Unit) {
    val t = UnraidTheme.colors
    Column {
        HorizontalDivider(color = t.border, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.bg)
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        ) {
            MainTab.values().forEach { tab ->
                val isActive = tab == active
                val tint = if (isActive) t.accent else t.muted
                val labelColor = if (isActive) t.text else t.muted
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onChange(tab) }
                        .padding(top = 6.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isActive) t.accentDim else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 4.dp),
                    ) {
                        when (tab) {
                            MainTab.Overview -> UC.Dashboard(20.dp, tint)
                            MainTab.Array    -> UC.Disk(20.dp, tint)
                            MainTab.Docker   -> UC.Docker(20.dp, tint)
                            MainTab.Vms      -> UC.Vm(20.dp, tint)
                        }
                    }
                    Text(
                        text = tab.label,
                        color = labelColor,
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
