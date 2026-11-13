package io.github.nofuturekid.nova.ui.screens.docker

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nofuturekid.nova.data.local.LayoutMode
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.Access
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.hasUpdate
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.ui.components.ContainerIcon
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.SectionLabel
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.components.UnraidIconButton
import io.github.nofuturekid.nova.ui.components.ViewOnly
import io.github.nofuturekid.nova.ui.components.ViewOnlyNote
import io.github.nofuturekid.nova.ui.screens.ErrorState
import io.github.nofuturekid.nova.ui.screens.ErrorSubject
import io.github.nofuturekid.nova.ui.screens.LoadingState
import io.github.nofuturekid.nova.ui.screens.NoServerState
import io.github.nofuturekid.nova.ui.screens.main.MainViewModel
import io.github.nofuturekid.nova.ui.theme.UnraidAlpha
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import io.github.nofuturekid.nova.ui.util.memUsed

@Composable
fun DockerTab(
    state: DomainState<List<Container>>,
    view: LayoutMode,
    query: String,
    onQueryChange: (String) -> Unit,
    onAddServer: () -> Unit,
    stats: Map<String, ContainerLiveStats> = emptyMap(),
    onOpenContainer: (Container) -> Unit,
    onStart: (Container) -> Unit,
    onRestart: (Container) -> Unit,
    onStop: (Container) -> Unit,
    onUpdateAll: () -> Unit,
    /** May the key start, stop, restart and update containers? Only [Access.Denied] hides the controls (#315). */
    control: Access = Access.Unknown,
) {
    when (state) {
        DomainState.Loading    -> LoadingState()
        DomainState.NoServer   -> NoServerState(onAdd = onAddServer)
        is DomainState.Error   -> ErrorState(
            state.message, detail = state.detail, reason = state.reason, subject = ErrorSubject.Docker,
        )
        is DomainState.Content -> if (state.value.isEmpty()) EmptyDocker() else DockerContent(
            containers = state.value,
            serverBaseUrl = state.serverBaseUrl,
            view = view,
            query = query,
            onQueryChange = onQueryChange,
            stats = stats,
            onOpenContainer = onOpenContainer,
            onStart = onStart,
            onRestart = onRestart,
            onStop = onStop,
            onUpdateAll = onUpdateAll,
            control = control,
        )
    }
}

/** Docker is on but has no containers: say so instead of showing only a search box. */
@Composable
private fun EmptyDocker() {
    val t = UnraidTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(60.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UC.Docker(36.dp, t.muted)
        Spacer(Modifier.height(8.dp))
        Text("No containers", color = t.text, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Containers you add in the Unraid web UI show up here.",
            color = t.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DockerContent(
    containers: List<Container>,
    serverBaseUrl: String,
    view: LayoutMode,
    query: String,
    onQueryChange: (String) -> Unit,
    stats: Map<String, ContainerLiveStats> = emptyMap(),
    onOpenContainer: (Container) -> Unit,
    onStart: (Container) -> Unit,
    onRestart: (Container) -> Unit,
    onStop: (Container) -> Unit,
    onUpdateAll: () -> Unit,
    control: Access = Access.Unknown,
) {
    val canControl = control != Access.Denied
    val filtered = filterContainers(containers, query)
    val rows = MainViewModel.joinContainerStats(filtered, stats)
    val noMatch = filtered.isEmpty() && containers.isNotEmpty()
    val updateCount = containers.count { it.updateStatus.hasUpdate() }
    val d = UnraidTheme.tokens
    when (view) {
        LayoutMode.List -> LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = d.screenPad, end = d.screenPad, top = 4.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(d.gap),
        ) {
            if (!canControl) item { ViewOnlyNote(ViewOnly.Containers) }
            if (updateCount > 0) item { UpdateAllBanner(updateCount, onUpdateAll, canControl) }
            item { SearchBox(query, onQueryChange) }
            if (noMatch) item { NoMatches(query, containers.size) }
            items(rows, key = { it.first.id }) { (c, live) ->
                ContainerRow(c, serverBaseUrl, live, onOpenContainer, onStart, onRestart, onStop, canControl)
            }
        }
        LayoutMode.Grid -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = d.screenPad, end = d.screenPad, top = 4.dp, bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(d.gap),
            verticalArrangement = Arrangement.spacedBy(d.gap),
        ) {
            if (!canControl) item(span = { GridItemSpan(maxLineSpan) }) { ViewOnlyNote(ViewOnly.Containers) }
            if (updateCount > 0) item(span = { GridItemSpan(maxLineSpan) }) {
                UpdateAllBanner(updateCount, onUpdateAll, canControl)
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SearchBox(query, onQueryChange) }
            if (noMatch) item(span = { GridItemSpan(maxLineSpan) }) { NoMatches(query, containers.size) }
            gridItems(filtered, key = { it.id }) { c ->
                ContainerGridTile(c, serverBaseUrl, onOpenContainer)
            }
        }
        LayoutMode.Grouped -> {
            val running = rows.filter { it.first.status == ContainerStatus.Running }
            val paused  = rows.filter { it.first.status == ContainerStatus.Paused }
            val exited  = rows.filter { it.first.status == ContainerStatus.Exited }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = d.screenPad, end = d.screenPad, top = 4.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(d.gap),
            ) {
                if (!canControl) item { ViewOnlyNote(ViewOnly.Containers) }
                if (updateCount > 0) item { UpdateAllBanner(updateCount, onUpdateAll, canControl) }
                item { SearchBox(query, onQueryChange) }
                if (noMatch) item { NoMatches(query, containers.size) }
                if (running.isNotEmpty()) {
                    item { SectionLabel("Running · ${running.size}") }
                    items(running, key = { "r-${it.first.id}" }) { (c, live) ->
                        ContainerRow(c, serverBaseUrl, live, onOpenContainer, onStart, onRestart, onStop, canControl)
                    }
                }
                if (paused.isNotEmpty()) {
                    item { SectionLabel("Paused · ${paused.size}") }
                    items(paused, key = { "p-${it.first.id}" }) { (c, live) ->
                        ContainerRow(c, serverBaseUrl, live, onOpenContainer, onStart, onRestart, onStop, canControl)
                    }
                }
                if (exited.isNotEmpty()) {
                    item { SectionLabel("Stopped · ${exited.size}") }
                    items(exited, key = { "x-${it.first.id}" }) { (c, live) ->
                        ContainerRow(c, serverBaseUrl, live, onOpenContainer, onStart, onRestart, onStop, canControl)
                    }
                }
            }
        }
        LayoutMode.Compose -> {
            val liveById = rows.associate { (c, live) -> c.id to live }
            val groups = groupByComposeProject(filtered)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = d.screenPad, end = d.screenPad, top = 4.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(d.gap),
            ) {
                if (!canControl) item { ViewOnlyNote(ViewOnly.Containers) }
                if (updateCount > 0) item { UpdateAllBanner(updateCount, onUpdateAll, canControl) }
                item { SearchBox(query, onQueryChange) }
                if (noMatch) item { NoMatches(query, containers.size) }
                groups.forEach { group ->
                    item(key = if (group.project == null) "g-none" else "g-p-${group.project}") {
                        SectionLabel("${group.title} · ${group.containers.size}")
                    }
                    items(group.containers, key = { "c-${it.id}" }) { c ->
                        ContainerRow(c, serverBaseUrl, liveById[c.id], onOpenContainer, onStart, onRestart, onStop, canControl)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateAllBanner(count: Int, onTap: () -> Unit, canUpdate: Boolean = true) {
    val t = UnraidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.info.copy(alpha = UnraidAlpha.softFill))
            .border(1.dp, t.info.copy(alpha = UnraidAlpha.softBorder), RoundedCornerShape(12.dp))
            .then(if (canUpdate) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UC.Refresh(18.dp, t.info)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (count == 1) "1 container has an update" else "$count containers have updates",
                color = t.info,
                style = MaterialTheme.typography.labelLarge,
            )
            if (canUpdate) {
                Text(
                    text = "Tap to update all",
                    color = t.info.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (canUpdate) UC.ChevR(16.dp, t.info)
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    val t = UnraidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(t.muted.copy(alpha = UnraidAlpha.softFill))
            .border(1.dp, t.border, CircleShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UC.Search(16.dp, t.muted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            cursorBrush = SolidColor(t.accent),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.text),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text("Search containers", color = t.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "Clear search" }
                    .clickable(role = Role.Button) { onChange("") },
                contentAlignment = Alignment.Center,
            ) {
                UC.X(16.dp, t.muted)
            }
        }
    }
}

@Composable
private fun NoMatches(query: String, total: Int) {
    val t = UnraidTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No matching containers", color = t.text, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Nothing named or based on \u201C${query.trim()}\u201D among $total containers.",
            color = t.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ContainerRow(
    c: Container,
    serverBaseUrl: String,
    liveStats: ContainerLiveStats?,
    onOpen: (Container) -> Unit,
    onStart: (Container) -> Unit,
    onRestart: (Container) -> Unit,
    onStop: (Container) -> Unit,
    canControl: Boolean = true,
) {
    val t = UnraidTheme.colors
    val tone = when (c.status) {
        ContainerStatus.Running -> Tone.Accent
        ContainerStatus.Paused  -> Tone.Warn
        ContainerStatus.Exited  -> Tone.Neutral
    }
    val statusLabel = when (c.status) {
        ContainerStatus.Running -> "running"
        ContainerStatus.Paused  -> "paused"
        ContainerStatus.Exited  -> "stopped"
    }
    UnraidCard(padding = UnraidTheme.tokens.padTight, onClick = { onOpen(c) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ContainerIcon(
                name = c.name,
                color = parseColor(c.iconColorHex) ?: t.accent,
                size = 40.dp,
                iconUrl = c.iconUrl,
                serverBaseUrl = serverBaseUrl,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = c.name,
                    color = t.text,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(statusLabel, tone = tone, dot = true)
                    if (c.updateStatus.hasUpdate()) {
                        Pill("update", tone = Tone.Info, dot = true)
                    }
                }
                if (liveStats != null && c.status == ContainerStatus.Running) {
                    Spacer(Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("%.1f%% CPU".format(liveStats.cpuPercent),
                            color = t.muted, style = MaterialTheme.typography.labelSmall)
                        Text(memUsed(liveStats.memUsage),
                            color = t.muted, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (canControl) Row {
                when (c.status) {
                    ContainerStatus.Running -> {
                        UnraidIconButton(icon = { UC.Restart(16.dp, t.text) }, onClick = { onRestart(c) }, size = 34.dp, contentDescription = "Restart ${c.name}")
                        UnraidIconButton(icon = { UC.Stop(14.dp, t.danger) }, onClick = { onStop(c) }, size = 34.dp, tone = Tone.Danger, contentDescription = "Stop ${c.name}")
                    }
                    ContainerStatus.Paused -> {
                        UnraidIconButton(icon = { UC.Play(16.dp, t.accent) }, onClick = { onStart(c) }, size = 34.dp, tone = Tone.Accent, contentDescription = "Resume ${c.name}")
                    }
                    ContainerStatus.Exited -> {
                        UnraidIconButton(icon = { UC.Play(16.dp, t.accent) }, onClick = { onStart(c) }, size = 34.dp, tone = Tone.Accent, contentDescription = "Start ${c.name}")
                    }
                }
            }
            UC.ChevR(tint = t.muted)
        }
    }
}

@Composable
private fun ContainerGridTile(c: Container, serverBaseUrl: String, onOpen: (Container) -> Unit) {
    val t = UnraidTheme.colors
    val dim = c.status != ContainerStatus.Running
    val statusColor = when (c.status) {
        ContainerStatus.Running -> t.accent
        ContainerStatus.Paused  -> t.warn
        ContainerStatus.Exited  -> t.muted
    }
    UnraidCard(padding = UnraidTheme.tokens.padTight, onClick = { onOpen(c) }) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (dim) 0.55f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        ContainerIcon(
                            name = c.name,
                            color = parseColor(c.iconColorHex) ?: t.accent,
                            size = 44.dp,
                            iconUrl = c.iconUrl,
                            serverBaseUrl = serverBaseUrl,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    if (c.updateStatus.hasUpdate()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(t.info),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = c.name,
                    color = t.text,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Subtle tap-affordance chevron — bottom-right corner so it
            // doesn't compete with the centered icon + label. Status and
            // update dots already occupy the top corners.
            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                UC.ChevR(14.dp, t.muted)
            }
        }
    }
}

private fun parseColor(hex: String?): Color? {
    if (hex == null) return null
    val h = hex.removePrefix("#")
    val n = h.toLongOrNull(16) ?: return null
    val argb = if (h.length == 6) (0xFF000000L or n) else n
    return Color(argb.toInt())
}
