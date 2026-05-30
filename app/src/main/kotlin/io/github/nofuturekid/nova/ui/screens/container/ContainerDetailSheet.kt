package io.github.nofuturekid.nova.ui.screens.container

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.core.net.toUri
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.hasUpdate
import io.github.nofuturekid.nova.ui.components.BtnVariant
import io.github.nofuturekid.nova.ui.components.ContainerIcon
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidButton
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.components.UnraidIconButton
import io.github.nofuturekid.nova.ui.components.onTone
import io.github.nofuturekid.nova.ui.theme.JetBrainsMono
import io.github.nofuturekid.nova.ui.theme.UnraidAlpha
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DetailTab { Info, Logs, Ports, Volumes }

// Logs tab re-fetches on this cadence while open (live tail). The
// unraid-api logs query returns a tail snapshot, not a stream, so we
// poll — matching the app's polling architecture (ADR-0017).
private const val LOG_TAIL_INTERVAL_MS = 3_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerDetailSheet(
    container: Container,
    serverBaseUrl: String,
    liveStats: ContainerLiveStats?,
    isUpdating: Boolean,
    onFetchLogs: suspend (id: String) -> List<io.github.nofuturekid.nova.data.model.LogLine>,
    onRefresh: suspend () -> Unit,
    onDismiss: () -> Unit,
    onStart: (Container) -> Unit,
    onRestart: (Container) -> Unit,
    onStop: (Container) -> Unit,
    onUpdate: (Container) -> Unit,
) {
    val t = UnraidTheme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(DetailTab.Info) }
    var logs by remember(container.id) { mutableStateOf<List<io.github.nofuturekid.nova.data.model.LogLine>?>(null) }
    var logsLoading by remember(container.id) { mutableStateOf(false) }
    // Live tail: while the Logs tab is open on a running container, re-fetch
    // on an interval. The keyed LaunchedEffect cancels the loop when the
    // tab changes, the container stops, or the sheet is dismissed.
    LaunchedEffect(tab, container.id, container.status) {
        if (tab == DetailTab.Logs && container.status == ContainerStatus.Running) {
            if (logs == null) logsLoading = true
            while (true) {
                try { logs = onFetchLogs(container.id) } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                logsLoading = false
                delay(LOG_TAIL_INTERVAL_MS)
            }
        }
    }

    val pullState = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }
    val onPull: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                try {
                    onRefresh()
                    if (tab == DetailTab.Logs && container.status == ContainerStatus.Running) {
                        runCatching { onFetchLogs(container.id) }.getOrNull()?.let { logs = it }
                    }
                } finally { refreshing = false }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.surface2,
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(t.muted.copy(alpha = UnraidAlpha.grabber)),
                )
            }
        },
    ) {
        Column(
            // Fixed sheet height so it doesn't jump when switching tabs
            // or when logs are empty / very long. Header + actions + tabs
            // stay pinned; only the tab content below scrolls (see the
            // weight(1f) + verticalScroll box further down).
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            val tone = when (container.status) {
                ContainerStatus.Running -> Tone.Accent
                ContainerStatus.Paused  -> Tone.Warn
                ContainerStatus.Exited  -> Tone.Neutral
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ContainerIcon(
                    name = container.name,
                    color = parseColor(container.iconColorHex) ?: t.accent,
                    size = 56.dp,
                    iconUrl = container.iconUrl,
                    serverBaseUrl = serverBaseUrl,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(container.name, color = t.text, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = container.image,
                        color = t.muted,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(label = container.status.name.lowercase(), tone = tone, dot = true)
                        if (container.updateStatus.hasUpdate()) {
                            Pill(label = "update available", tone = Tone.Info, dot = true)
                        }
                    }
                }
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss, contentDescription = "Close")
            }
            Spacer(Modifier.height(18.dp))

            if (isUpdating) {
                // Mutation is in flight (image pull + recreate, can take
                // minutes). Suppress all lifecycle actions to avoid the user
                // hitting Start/Stop while the server is recreating the
                // container. Snapshot polling clears this state once the
                // new container is RUNNING.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(t.info.copy(alpha = UnraidAlpha.softFill))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = t.info,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Updating container…",
                        color = t.info,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                if (container.updateStatus.hasUpdate()) {
                    UnraidButton(
                        onClick = { onUpdate(container) },
                        label = "Update container",
                        modifier = Modifier.fillMaxWidth(),
                        variant = BtnVariant.Filled,
                        tone = Tone.Info,
                        fullWidth = true,
                        leadingIcon = { UC.Refresh(14.dp, onTone(t.info)) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (container.status == ContainerStatus.Running) {
                        UnraidButton(
                            onClick = { onRestart(container) },
                            label = "Restart",
                            modifier = Modifier.weight(1f),
                            variant = BtnVariant.Tonal,
                            fullWidth = true,
                            leadingIcon = { UC.Restart(14.dp, t.accent) },
                        )
                        UnraidButton(
                            onClick = { onStop(container) },
                            label = "Stop",
                            modifier = Modifier.weight(1f),
                            variant = BtnVariant.Tonal,
                            tone = Tone.Danger,
                            fullWidth = true,
                            leadingIcon = { UC.Stop(14.dp, t.danger) },
                        )
                    } else {
                        UnraidButton(
                            onClick = { onStart(container) },
                            label = "Start",
                            modifier = Modifier.weight(1f),
                            variant = BtnVariant.Filled,
                            fullWidth = true,
                            leadingIcon = { UC.Play(14.dp, onTone(t.accent)) },
                        )
                    }
                }
            }
            container.webUiUrl?.let { url ->
                Spacer(Modifier.height(8.dp))
                val context = LocalContext.current
                // Macvlan / ipvlan containers (br0 etc.) are reachable on
                // their own LAN IP, not the Unraid host's. The unraid-api's
                // resolved webUiUrl substitutes [IP] with the host's IP,
                // which is correct for bridge-mode containers but wrong
                // for containers that hold their own LAN IP. When we have
                // a non-default-bridge network IP, swap the URL's host
                // for that.
                val targetUrl = container.networkIp
                    ?.let { ip -> replaceUrlHost(url, ip) }
                    ?: url
                UnraidButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, targetUrl.toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    label = "Open Web UI",
                    modifier = Modifier.fillMaxWidth(),
                    variant = BtnVariant.Tonal,
                    fullWidth = true,
                    leadingIcon = { UC.Link(14.dp, t.accent) },
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.muted.copy(alpha = UnraidAlpha.softFill))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailTab.values().forEach { tk ->
                    val isActive = tk == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isActive) t.accentDim else Color.Transparent)
                            .clickable { tab = tk }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tk.name,
                            color = if (isActive) t.accent else t.muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // Only the tab content scrolls; the header/actions/tabs above
            // stay fixed. weight(1f) makes it take the remaining height of
            // the fixed-height sheet. Pull-to-refresh wraps just this
            // scrollable region so its gesture doesn't fight the sheet's
            // own drag-to-dismiss on the pinned header.
            PullToRefreshBox(
                isRefreshing = refreshing,
                state = pullState,
                onRefresh = onPull,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (tab) {
                        DetailTab.Info    -> InfoTabContent(container, liveStats)
                        DetailTab.Logs    -> LogsTabContent(container, logs, logsLoading)
                        DetailTab.Ports   -> PortsTabContent(container)
                        DetailTab.Volumes -> VolumesTabContent(container)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoTabContent(c: Container, liveStats: ContainerLiveStats?) {
    val t = UnraidTheme.colors
    // Live per-container stats arrive only via the dockerContainerStats
    // subscription overlay (the polled Container carries 0). They're
    // meaningless for a stopped container, so gate on Running + a frame.
    val live = liveStats?.takeIf { c.status == ContainerStatus.Running }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        UnraidCard(padding = UnraidTheme.tokens.pad) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatBlock(
                        label = "CPU",
                        value = live?.let { "%.1f%%".format(it.cpuPercent) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    StatBlock(
                        label = "Memory",
                        value = live?.memUsage ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatBlock(
                        label = "Network",
                        value = live?.netIO ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    StatBlock(
                        label = "Disk I/O",
                        value = live?.blockIO ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        UnraidCard(padding = UnraidTheme.tokens.pad) {
            Column {
                Kv("Image", c.image, mono = true)
                Kv("Auto-start", if (c.autoStart) "Enabled" else "Disabled")
                Kv("Status", c.status.name, last = true)
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    val t = UnraidTheme.colors
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            color = t.muted,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = t.text,
            // titleLarge (not headlineMedium): the live memory/net/disk
            // values are preformatted "used / limit" pairs that overflow a
            // half-width column at headline size; titleLarge keeps them on
            // one ellipsized line while staying the row's emphasis weight.
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Kv(key: String, value: String, mono: Boolean = false, last: Boolean = false) {
    val t = UnraidTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(key, color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = value,
                color = t.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (mono) JetBrainsMono else null,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!last) HorizontalDivider(color = t.border)
    }
}

@Composable
private fun LogsTabContent(
    c: Container,
    logs: List<io.github.nofuturekid.nova.data.model.LogLine>?,
    loading: Boolean,
) {
    val t = UnraidTheme.colors
    if (c.status != ContainerStatus.Running) {
        Text(
            text = "Logs are only available when the container is running.",
            color = t.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp),
        )
        return
    }
    if (loading || logs == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = t.accent)
        }
        return
    }
    if (logs.isEmpty()) {
        Text(
            text = "No log lines.",
            color = t.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Theme-correct log surface (was a hardcoded near-black
            // "terminal"): t.bg stays dark in dark mode, light in light
            // mode; border keeps it defined against the sheet in both.
            .background(t.bg, RoundedCornerShape(12.dp))
            .border(1.dp, t.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        logs.forEach { line ->
            val color = when {
                line.message.contains("error", ignoreCase = true) -> t.danger
                line.message.contains("warn",  ignoreCase = true) -> t.warn
                line.message.contains("debug", ignoreCase = true) -> t.muted
                else                                              -> t.text
            }
            Row {
                Text(
                    text = formatLogTime(line.time),
                    color = t.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = line.message,
                    color = color,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMono,
                        lineHeight = 16.sp,
                    ),
                )
            }
        }
    }
}

private fun formatLogTime(iso: String): String {
    // Strip the date portion for compactness: "2026-05-13T18:24:07.123Z" → "18:24:07"
    val tIdx = iso.indexOf('T')
    if (tIdx < 0) return iso
    val rest = iso.substring(tIdx + 1)
    val cut = rest.indexOfAny(charArrayOf('.', 'Z', '+', '-'))
    return if (cut > 0) rest.substring(0, cut) else rest
}

@Composable
private fun PortsTabContent(c: Container) {
    val t = UnraidTheme.colors
    if (c.ports.isEmpty()) {
        Empty("No port mappings")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        c.ports.forEach { mapping ->
            val parts = mapping.split(':')
            val host = parts.getOrNull(0) ?: mapping
            val ctn = parts.getOrNull(1) ?: ""
            UnraidCard(padding = UnraidTheme.tokens.padTight) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UC.More(18.dp, t.muted)
                    Row(modifier = Modifier.weight(1f)) {
                        Text(host, color = t.accent, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono))
                        Text(" → ", color = t.muted, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono))
                        Text(ctn, color = t.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono))
                    }
                    UnraidIconButton(icon = { UC.Link(16.dp, t.muted) }, onClick = {}, size = 32.dp, contentDescription = "Open port $host")
                }
            }
        }
    }
}

@Composable
private fun VolumesTabContent(c: Container) {
    val t = UnraidTheme.colors
    if (c.volumes.isEmpty()) {
        Empty("No volume mounts")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        c.volumes.forEach { mount ->
            UnraidCard(padding = UnraidTheme.tokens.padTight) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UC.Folder(18.dp, t.muted)
                    Text(mount, color = t.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono))
                }
            }
        }
    }
}

@Composable
private fun Empty(label: String) {
    val t = UnraidTheme.colors
    Text(
        text = label,
        color = t.muted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp),
    )
}

private fun parseColor(hex: String?): Color? {
    if (hex == null) return null
    val h = hex.removePrefix("#")
    val n = h.toLongOrNull(16) ?: return null
    val argb = if (h.length == 6) (0xFF000000L or n) else n
    return Color(argb.toInt())
}

/**
 * Replace the host portion of [url] with [newHost], preserving scheme,
 * port, path and query. Falls back to [url] verbatim if parsing fails.
 */
private fun replaceUrlHost(url: String, newHost: String): String = runCatching {
    val uri = url.toUri()
    val portSuffix = if (uri.port > 0) ":${uri.port}" else ""
    val authority = "$newHost$portSuffix"
    android.net.Uri.Builder()
        .scheme(uri.scheme)
        .encodedAuthority(authority)
        .encodedPath(uri.encodedPath)
        .encodedQuery(uri.encodedQuery)
        .encodedFragment(uri.encodedFragment)
        .build()
        .toString()
}.getOrDefault(url)
