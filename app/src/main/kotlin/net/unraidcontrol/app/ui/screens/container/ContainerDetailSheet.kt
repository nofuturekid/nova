package net.unraidcontrol.app.ui.screens.container

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import net.unraidcontrol.app.data.model.Container
import net.unraidcontrol.app.data.model.ContainerStatus
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.ContainerIcon
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.components.UnraidCard
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.theme.JetBrainsMono
import net.unraidcontrol.app.ui.theme.UnraidTheme

private enum class DetailTab { Info, Logs, Ports, Volumes }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerDetailSheet(
    container: Container,
    serverBaseUrl: String,
    onFetchLogs: suspend (id: String) -> List<net.unraidcontrol.app.data.model.LogLine>,
    onFetchMounts: suspend (id: String) -> List<String>,
    onDismiss: () -> Unit,
    onStart: (Container) -> Unit,
    onRestart: (Container) -> Unit,
    onStop: (Container) -> Unit,
) {
    val t = UnraidTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(DetailTab.Info) }
    var logs by remember(container.id) { mutableStateOf<List<net.unraidcontrol.app.data.model.LogLine>?>(null) }
    var logsLoading by remember(container.id) { mutableStateOf(false) }
    var mounts by remember(container.id) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(tab, container.id, container.status) {
        if (tab == DetailTab.Logs && container.status == ContainerStatus.Running && logs == null) {
            logsLoading = true
            try { logs = onFetchLogs(container.id) }
            finally { logsLoading = false }
        }
        if (tab == DetailTab.Volumes && mounts == null) {
            mounts = onFetchMounts(container.id)
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
                        .background(Color.White.copy(alpha = 0.18f)),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
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
                    Text(container.name, color = t.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = container.image,
                        color = t.muted,
                        fontSize = 12.sp,
                        fontFamily = JetBrainsMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Pill(label = container.status.name.lowercase(), tone = tone, dot = true)
                }
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss)
            }
            Spacer(Modifier.height(18.dp))

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
                        leadingIcon = { UC.Play(14.dp, Color(0xFF06120E)) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailTab.values().forEach { tk ->
                    val isActive = tk == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isActive) t.surface else Color.Transparent)
                            .clickable { tab = tk }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tk.name,
                            color = if (isActive) t.text else t.muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            when (tab) {
                DetailTab.Info    -> InfoTabContent(container)
                DetailTab.Logs    -> LogsTabContent(container, logs, logsLoading)
                DetailTab.Ports   -> PortsTabContent(container)
                DetailTab.Volumes -> VolumesTabContent(container, mounts)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoTabContent(c: Container) {
    val t = UnraidTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        UnraidCard(padding = 14.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatBlock(label = "CPU", value = "%.1f%%".format(c.cpu), modifier = Modifier.weight(1f))
                StatBlock(
                    label = "Memory",
                    value = if (c.memMb > 0) "${c.memMb} MB" else "—",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        UnraidCard(padding = 14.dp) {
            Column {
                Kv("Image", c.image, mono = true)
                Kv("Container ID", c.id)
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
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = t.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
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
            Text(key, color = t.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                text = value,
                color = t.text,
                fontSize = 13.sp,
                fontFamily = if (mono) JetBrainsMono else null,
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
    logs: List<net.unraidcontrol.app.data.model.LogLine>?,
    loading: Boolean,
) {
    val t = UnraidTheme.colors
    if (c.status != ContainerStatus.Running) {
        Text(
            text = "Logs are only available when the container is running.",
            color = t.muted,
            fontSize = 13.sp,
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
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF06090A), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        logs.forEach { line ->
            val color = when {
                line.message.contains("error", ignoreCase = true) -> t.danger
                line.message.contains("warn",  ignoreCase = true) -> t.warn
                line.message.contains("debug", ignoreCase = true) -> t.muted
                else                                              -> t.accent
            }
            Row {
                Text(
                    text = formatLogTime(line.time),
                    color = t.muted,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMono,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = line.message,
                    color = color,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMono,
                    lineHeight = 16.sp,
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
            UnraidCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UC.More(18.dp, t.muted)
                    Row(modifier = Modifier.weight(1f)) {
                        Text(host, color = t.accent, fontSize = 13.sp, fontFamily = JetBrainsMono)
                        Text(" → ", color = t.muted, fontSize = 13.sp, fontFamily = JetBrainsMono)
                        Text(ctn, color = t.text, fontSize = 13.sp, fontFamily = JetBrainsMono)
                    }
                    UnraidIconButton(icon = { UC.Link(16.dp, t.muted) }, onClick = {}, size = 32.dp)
                }
            }
        }
    }
}

@Composable
private fun VolumesTabContent(c: Container, mounts: List<String>?) {
    val t = UnraidTheme.colors
    if (mounts == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = t.accent)
        }
        return
    }
    if (mounts.isEmpty()) {
        Empty("No volume mounts")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        mounts.forEach { mount ->
            UnraidCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UC.Folder(18.dp, t.muted)
                    Text(mount, color = t.text, fontSize = 12.sp, fontFamily = JetBrainsMono)
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
        fontSize = 13.sp,
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
