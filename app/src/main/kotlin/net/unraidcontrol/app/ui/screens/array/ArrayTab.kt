package net.unraidcontrol.app.ui.screens.array

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.data.local.LayoutMode
import net.unraidcontrol.app.data.model.ArrayInfo
import net.unraidcontrol.app.data.model.ArrayState
import net.unraidcontrol.app.data.model.Disk
import net.unraidcontrol.app.data.model.DiskType
import net.unraidcontrol.app.data.repository.DomainState
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.SectionLabel
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.components.UnraidCard
import net.unraidcontrol.app.ui.components.UnraidProgress
import net.unraidcontrol.app.ui.screens.ErrorState
import net.unraidcontrol.app.ui.screens.LoadingState
import net.unraidcontrol.app.ui.screens.NoServerState
import net.unraidcontrol.app.ui.theme.JetBrainsMono
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun ArrayTab(
    state: DomainState<ArrayInfo>,
    view: LayoutMode,
    onAddServer: () -> Unit,
    onStartArray: () -> Unit,
    onStopArray: () -> Unit,
    onStartParity: () -> Unit,
    onPauseParity: () -> Unit,
    onResumeParity: () -> Unit,
    onCancelParity: () -> Unit,
) {
    when (state) {
        DomainState.Loading    -> LoadingState()
        DomainState.NoServer   -> NoServerState(onAdd = onAddServer)
        is DomainState.Error   -> ErrorState(state.message)
        is DomainState.Content -> ArrayContent(
            state.value, view, onStartArray, onStopArray,
            onStartParity, onPauseParity, onResumeParity, onCancelParity,
        )
    }
}

@Composable
private fun ArrayContent(
    arr: ArrayInfo,
    view: LayoutMode,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartParity: () -> Unit,
    onPauseParity: () -> Unit,
    onResumeParity: () -> Unit,
    onCancelParity: () -> Unit,
) {
    val t = UnraidTheme.colors
    val arrayOn = arr.state != ArrayState.Stopped && arr.state != ArrayState.Offline
    val parity  = arr.state == ArrayState.Parity
    val errored = arr.state == ArrayState.Error
    val dataDisks = arr.disks.filter { it.type == DiskType.Data }
    val totalUsedTb = dataDisks.sumOf { it.usedTb }
    val totalSizeTb = dataDisks.sumOf { it.sizeTb }

    val d = UnraidTheme.tokens
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = d.screenPad, end = d.screenPad, top = 4.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(d.gap),
    ) {
        item {
            UnraidCard(padding = UnraidTheme.tokens.padHero) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                UC.Shield(18.dp, t.muted)
                                Text("Array status", color = t.muted, style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = when {
                                    !arrayOn -> "Stopped"
                                    errored  -> "Disk error"
                                    parity   -> "Parity check"
                                    else     -> "Started"
                                },
                                color = t.text,
                                style = MaterialTheme.typography.headlineLarge,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${arr.disks.size} disks · ${"%.1f".format(totalUsedTb)} / ${"%.0f".format(totalSizeTb)} TB",
                                color = t.muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (arrayOn) {
                            UnraidButton(
                                onClick = onStop,
                                label = "Stop",
                                variant = BtnVariant.Tonal,
                                tone = Tone.Danger,
                                leadingIcon = { UC.Stop(14.dp, t.danger) },
                            )
                        } else {
                            UnraidButton(
                                onClick = onStart,
                                label = "Start",
                                variant = BtnVariant.Filled,
                                leadingIcon = { UC.Play(14.dp, Color(0xFF06120E)) },
                            )
                        }
                    }
                    val pc = arr.parity
                    if (pc != null) {
                        Spacer(Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(t.info.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .border(1.dp, t.info.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Row {
                                Text(
                                    if (pc.paused) "Parity check paused" else "Parity check in progress",
                                    color = t.info,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${(pc.progress * 100).toInt()}%",
                                    color = t.info,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            UnraidProgress(pc.progress, color = t.info, height = 6.dp)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Text(
                                    "${"%.0f".format(pc.speedMbps)} MB/s · ${pc.errors} errors",
                                    color = t.muted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.weight(1f))
                                val eta = formatEta(pc.etaSeconds)
                                if (eta.isNotEmpty()) Text("~$eta remaining", color = t.muted, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (pc.paused) {
                                    UnraidButton(
                                        onClick = onResumeParity,
                                        label = "Resume",
                                        modifier = Modifier.weight(1f),
                                        variant = BtnVariant.Tonal,
                                        fullWidth = true,
                                        leadingIcon = { UC.Play(14.dp, t.accent) },
                                    )
                                } else {
                                    UnraidButton(
                                        onClick = onPauseParity,
                                        label = "Pause",
                                        modifier = Modifier.weight(1f),
                                        variant = BtnVariant.Tonal,
                                        tone = Tone.Warn,
                                        fullWidth = true,
                                        leadingIcon = { UC.Pause(14.dp, t.warn) },
                                    )
                                }
                                UnraidButton(
                                    onClick = onCancelParity,
                                    label = "Cancel",
                                    modifier = Modifier.weight(1f),
                                    variant = BtnVariant.Tonal,
                                    tone = Tone.Danger,
                                    fullWidth = true,
                                    leadingIcon = { UC.Stop(14.dp, t.danger) },
                                )
                            }
                        }
                    } else if (arrayOn) {
                        Spacer(Modifier.height(12.dp))
                        UnraidButton(
                            onClick = onStartParity,
                            label = "Check parity",
                            modifier = Modifier.fillMaxWidth(),
                            variant = BtnVariant.Tonal,
                            fullWidth = true,
                            leadingIcon = { UC.Shield(14.dp, t.accent) },
                        )
                    }
                    if (errored) {
                        Spacer(Modifier.height(16.dp))
                        val erroredDisk = arr.disks.firstOrNull { it.status == net.unraidcontrol.app.data.model.DiskStatus.Error }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(t.danger.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .border(1.dp, t.danger.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            UC.Alert(18.dp, t.danger)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${erroredDisk?.name ?: "disk"} read errors",
                                    color = t.danger,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Read errors detected. Investigate before next array operation.",
                                    color = t.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        fun isErr(disk: Disk) =
            errored && disk.status == net.unraidcontrol.app.data.model.DiskStatus.Error

        when (view) {
            LayoutMode.List -> {
                item { SectionLabel("Disks") }
                items(arr.disks, key = { it.name }) { disk ->
                    DiskCard(disk, errored = isErr(disk))
                }
            }
            LayoutMode.Grid -> {
                item { SectionLabel("Disks") }
                items(arr.disks.chunked(2), key = { it.first().name }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(d.gap)) {
                        row.forEach { disk ->
                            Box(modifier = Modifier.weight(1f)) {
                                DiskTile(disk, errored = isErr(disk))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            LayoutMode.Grouped -> {
                val byType = listOf(
                    "Parity" to arr.disks.filter { it.type == DiskType.Parity },
                    "Data"   to arr.disks.filter { it.type == DiskType.Data },
                    "Cache"  to arr.disks.filter { it.type == DiskType.Cache },
                )
                byType.forEach { (label, disks) ->
                    if (disks.isNotEmpty()) {
                        item(key = "h-$label") { SectionLabel("$label · ${disks.size}") }
                        items(disks, key = { "$label-${it.name}" }) { disk ->
                            DiskCard(disk, errored = isErr(disk))
                        }
                    }
                }
            }
        }
    }
}

/** Compact disk tile for the Grid layout — type icon, name, temp,
 *  usage bar (data/cache only). */
@Composable
private fun DiskTile(disk: Disk, errored: Boolean) {
    val t = UnraidTheme.colors
    val typeColor = when (disk.type) {
        DiskType.Parity -> Color(0xFFF59E0B)
        DiskType.Cache  -> Color(0xFFA78BFA)
        DiskType.Data   -> t.accent
    }
    val tempColor = when {
        disk.tempC >= 50 -> t.danger
        disk.tempC >= 42 -> t.warn
        else             -> t.muted
    }
    val pct = if (disk.sizeTb > 0) (disk.usedTb / disk.sizeTb).toFloat() else 0f
    UnraidCard(padding = UnraidTheme.tokens.padTight) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(typeColor.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) { UC.Disk(15.dp, typeColor) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        disk.name,
                        color = t.text,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${disk.tempC}°",
                        color = tempColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    )
                }
                if (errored) Pill("ERR", tone = Tone.Danger, dot = true)
            }
            if (disk.type != DiskType.Parity) {
                Spacer(Modifier.height(8.dp))
                UnraidProgress(pct, color = if (errored) t.danger else typeColor, height = 4.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(pct * 100).toInt()}% · ${formatTb(disk.sizeTb)}",
                    color = t.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                )
            }
        }
    }
}

@Composable
private fun DiskCard(disk: Disk, errored: Boolean) {
    val t = UnraidTheme.colors
    val typeColor = when (disk.type) {
        DiskType.Parity -> Color(0xFFF59E0B)
        DiskType.Cache  -> Color(0xFFA78BFA)
        DiskType.Data   -> t.accent
    }
    val tempColor = when {
        disk.tempC >= 50 -> t.danger
        disk.tempC >= 42 -> t.warn
        else             -> t.muted
    }
    val pct = if (disk.sizeTb > 0) (disk.usedTb / disk.sizeTb).toFloat() else 0f
    UnraidCard(padding = UnraidTheme.tokens.pad) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(typeColor.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) { UC.Disk(18.dp, typeColor) }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(disk.name, color = t.text, style = MaterialTheme.typography.titleSmall)
                        if (errored) Pill("ERR", tone = Tone.Danger, dot = true)
                    }
                    Text(
                        text = "${disk.device} · ${disk.model}",
                        color = t.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    UC.Thermo(13.dp, tempColor)
                    Text(
                        text = "${disk.tempC}°",
                        color = tempColor,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono),
                    )
                }
            }
            if (disk.type != DiskType.Parity) {
                Spacer(Modifier.height(10.dp))
                UnraidProgress(pct, color = if (errored) t.danger else typeColor, height = 5.dp)
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        text = formatTb(disk.usedTb) + " used",
                        color = t.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${(pct * 100).toInt()}% · ${formatTb(disk.sizeTb)}",
                        color = t.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    )
                }
            }
        }
    }
}

private fun formatTb(tb: Double): String =
    if (tb < 1) "${(tb * 1000).toInt()} GB" else "%.2f TB".format(tb)

private fun formatEta(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
