package io.github.nofuturekid.nova.ui.screens.array

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
import io.github.nofuturekid.nova.data.local.LayoutMode
import io.github.nofuturekid.nova.data.model.Access
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.Disk
import io.github.nofuturekid.nova.data.model.DiskTempLevel
import io.github.nofuturekid.nova.data.model.DiskType
import io.github.nofuturekid.nova.data.model.tempLevel
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.ui.components.BtnVariant
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.SectionLabel
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidButton
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.components.UnraidProgress
import io.github.nofuturekid.nova.ui.components.ViewOnly
import io.github.nofuturekid.nova.ui.components.ViewOnlyNote
import io.github.nofuturekid.nova.ui.components.onTone
import io.github.nofuturekid.nova.ui.screens.ErrorState
import io.github.nofuturekid.nova.ui.screens.ErrorSubject
import io.github.nofuturekid.nova.ui.screens.LoadingState
import io.github.nofuturekid.nova.ui.screens.NoServerState
import io.github.nofuturekid.nova.ui.theme.JetBrainsMono
import io.github.nofuturekid.nova.ui.theme.UnraidAlpha
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import io.github.nofuturekid.nova.ui.util.countLabel
import io.github.nofuturekid.nova.ui.util.diskSubtitle

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
    globalDiskWarnC: Int? = null,
    globalDiskCritC: Int? = null,
    /** May the key start and stop the array and run parity checks? Only [Access.Denied] hides the buttons (#315). */
    control: Access = Access.Unknown,
) {
    when (state) {
        DomainState.Loading    -> LoadingState()
        DomainState.NoServer   -> NoServerState(onAdd = onAddServer)
        is DomainState.Error   -> ErrorState(
            state.message, detail = state.detail, reason = state.reason, subject = ErrorSubject.Array,
        )
        is DomainState.Content -> ArrayContent(
            state.value, view, onStartArray, onStopArray,
            onStartParity, onPauseParity, onResumeParity, onCancelParity,
            globalDiskWarnC, globalDiskCritC,
            canControl = control != Access.Denied,
        )
    }
}

/**
 * False for a server whose storage is all in pools (#217): no data and no
 * parity disk, so there is no array to start, stop or check. Decided by the
 * disks, not by the array state, which such a server reports in a way nobody
 * has recorded yet.
 */
internal fun ArrayInfo.hasArrayDisks(): Boolean =
    disks.any { it.type == DiskType.Data || it.type == DiskType.Parity }

/** Shown instead of the status card (and its Start, Stop and parity buttons) when there is no array. */
@Composable
private fun NoArrayCard() {
    val t = UnraidTheme.colors
    UnraidCard(padding = UnraidTheme.tokens.padHero) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UC.Disk(24.dp, t.muted)
            Column {
                Text("This server has no array", color = t.text, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text("Its storage is in pools.", color = t.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
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
    globalDiskWarnC: Int? = null,
    globalDiskCritC: Int? = null,
    canControl: Boolean = true,
) {
    val t = UnraidTheme.colors
    val arrayOn = arr.state != ArrayState.Stopped && arr.state != ArrayState.Offline
    val parity  = arr.state == ArrayState.Parity
    val errored = arr.state == ArrayState.Error
    val hasArray = arr.hasArrayDisks()
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
        if (!hasArray) item { NoArrayCard() }
        // Without an array there is nothing to control, so the note only belongs to a real array.
        if (hasArray && !canControl) item { ViewOnlyNote(ViewOnly.Array) }
        if (hasArray) item {
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
                                    parity   -> "Parity operation"
                                    else     -> "Started"
                                },
                                color = t.text,
                                style = MaterialTheme.typography.headlineLarge,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${countLabel(arr.disks.size, "disk")} · ${"%.1f".format(totalUsedTb)} / ${"%.0f".format(totalSizeTb)} TB",
                                color = t.muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (canControl && arrayOn) {
                            UnraidButton(
                                onClick = onStop,
                                label = "Stop",
                                variant = BtnVariant.Tonal,
                                tone = Tone.Danger,
                                leadingIcon = { UC.Stop(14.dp, t.danger) },
                            )
                        } else if (canControl) {
                            UnraidButton(
                                onClick = onStart,
                                label = "Start",
                                variant = BtnVariant.Filled,
                                leadingIcon = { UC.Play(14.dp, onTone(t.accent)) },
                            )
                        }
                    }
                    val pc = arr.parity
                    if (pc != null) {
                        Spacer(Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(t.info.copy(alpha = UnraidAlpha.softFill), RoundedCornerShape(12.dp))
                                .border(1.dp, t.info.copy(alpha = UnraidAlpha.softBorder), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Row {
                                Text(
                                    if (pc.paused) "Parity operation paused" else "Parity operation in progress",
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
                                    "${"%.0f".format(pc.speedMbps)} MB/s",
                                    color = t.muted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (canControl) Spacer(Modifier.height(10.dp))
                            if (canControl) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    } else if (canControl && arrayOn && !parity) {
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
                        val erroredDisk = arr.disks.firstOrNull { it.status == io.github.nofuturekid.nova.data.model.DiskStatus.Error }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(t.danger.copy(alpha = UnraidAlpha.softFill), RoundedCornerShape(12.dp))
                                .border(1.dp, t.danger.copy(alpha = UnraidAlpha.softBorder), RoundedCornerShape(12.dp))
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
            errored && disk.status == io.github.nofuturekid.nova.data.model.DiskStatus.Error

        when (view) {
            LayoutMode.List -> {
                val arrayDisks = arr.disks.filter { it.type == DiskType.Parity } +
                    arr.disks.filter { it.type == DiskType.Data }
                val cacheDisks = arr.disks.filter { it.type == DiskType.Cache }
                if (arrayDisks.isNotEmpty()) {
                    item { SectionLabel("Array") }
                    items(arrayDisks, key = { it.name }) { disk ->
                        DiskCard(disk, errored = isErr(disk), globalDiskWarnC = globalDiskWarnC, globalDiskCritC = globalDiskCritC)
                    }
                }
                if (cacheDisks.isNotEmpty()) {
                    item { SectionLabel("Cache") }
                    items(cacheDisks, key = { it.name }) { disk ->
                        DiskCard(disk, errored = isErr(disk), globalDiskWarnC = globalDiskWarnC, globalDiskCritC = globalDiskCritC)
                    }
                }
            }
            LayoutMode.Grid -> {
                val arrayDisks = arr.disks.filter { it.type == DiskType.Parity } +
                    arr.disks.filter { it.type == DiskType.Data }
                val cacheDisks = arr.disks.filter { it.type == DiskType.Cache }
                if (arrayDisks.isNotEmpty()) {
                    item { SectionLabel("Array") }
                    items(arrayDisks.chunked(2), key = { it.first().name }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(d.gap)) {
                            row.forEach { disk ->
                                Box(modifier = Modifier.weight(1f)) {
                                    DiskTile(disk, errored = isErr(disk), globalDiskWarnC = globalDiskWarnC, globalDiskCritC = globalDiskCritC)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (cacheDisks.isNotEmpty()) {
                    item { SectionLabel("Cache") }
                    items(cacheDisks.chunked(2), key = { it.first().name }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(d.gap)) {
                            row.forEach { disk ->
                                Box(modifier = Modifier.weight(1f)) {
                                    DiskTile(disk, errored = isErr(disk), globalDiskWarnC = globalDiskWarnC, globalDiskCritC = globalDiskCritC)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            LayoutMode.Grouped, LayoutMode.Compose -> {
                val byType = listOf(
                    "Parity" to arr.disks.filter { it.type == DiskType.Parity },
                    "Data"   to arr.disks.filter { it.type == DiskType.Data },
                    "Cache"  to arr.disks.filter { it.type == DiskType.Cache },
                )
                byType.forEach { (label, disks) ->
                    if (disks.isNotEmpty()) {
                        item(key = "h-$label") { SectionLabel("$label · ${disks.size}") }
                        items(disks, key = { "$label-${it.name}" }) { disk ->
                            DiskCard(disk, errored = isErr(disk), globalDiskWarnC = globalDiskWarnC, globalDiskCritC = globalDiskCritC)
                        }
                    }
                }
            }
        }
    }
}

/** Compact disk tile for the Grid layout — type icon, name, temp/standby,
 *  usage bar (data/cache only). */
@Composable
private fun DiskTile(disk: Disk, errored: Boolean, globalDiskWarnC: Int? = null, globalDiskCritC: Int? = null) {
    val t = UnraidTheme.colors
    val typeColor = when (disk.type) {
        DiskType.Parity -> Color(0xFFF59E0B)
        DiskType.Cache  -> Color(0xFFA78BFA)
        DiskType.Data   -> t.accent
    }
    val level = disk.tempLevel(globalDiskWarnC, globalDiskCritC)
    val tempColor = when (level) {
        DiskTempLevel.Danger  -> t.danger
        DiskTempLevel.Warn    -> t.warn
        else                  -> t.muted
    }
    val pct = if (disk.sizeTb > 0) (disk.usedTb / disk.sizeTb).toFloat() else 0f
    UnraidCard(padding = UnraidTheme.tokens.padTight) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(typeColor.copy(alpha = UnraidAlpha.tonalFill)),
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
                    if (level == DiskTempLevel.Standby) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            UC.Standby(11.dp, t.muted)
                            Text(
                                "Standby",
                                color = t.muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        Text(
                            "${disk.tempC}°",
                            color = tempColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        )
                    }
                }
                if (disk.numErrors > 0) Pill("${disk.numErrors} err", tone = Tone.Danger, dot = true)
                else if (errored) Pill("ERR", tone = Tone.Danger, dot = true)
            }
            if (disk.type != DiskType.Parity && !disk.isPoolMember) {
                Spacer(Modifier.height(8.dp))
                UnraidProgress(pct, color = if (errored) t.danger else typeColor, height = 4.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(pct * 100).toInt()}% · ${formatTb(disk.sizeTb)}",
                    color = t.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                )
            } else if (disk.isPoolMember) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill("Pool", tone = Tone.Info)
                    Text(
                        formatTb(disk.sizeTb),
                        color = t.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiskCard(disk: Disk, errored: Boolean, globalDiskWarnC: Int? = null, globalDiskCritC: Int? = null) {
    val t = UnraidTheme.colors
    val typeColor = when (disk.type) {
        DiskType.Parity -> Color(0xFFF59E0B)
        DiskType.Cache  -> Color(0xFFA78BFA)
        DiskType.Data   -> t.accent
    }
    val level = disk.tempLevel(globalDiskWarnC, globalDiskCritC)
    val tempColor = when (level) {
        DiskTempLevel.Danger  -> t.danger
        DiskTempLevel.Warn    -> t.warn
        else                  -> t.muted
    }
    val pct = if (disk.sizeTb > 0) (disk.usedTb / disk.sizeTb).toFloat() else 0f
    UnraidCard(padding = UnraidTheme.tokens.pad) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(typeColor.copy(alpha = UnraidAlpha.tonalFill)),
                    contentAlignment = Alignment.Center,
                ) { UC.Disk(18.dp, typeColor) }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(disk.name, color = t.text, style = MaterialTheme.typography.titleSmall)
                        if (disk.numErrors > 0) Pill("${disk.numErrors} err", tone = Tone.Danger, dot = true)
                        else if (errored) Pill("ERR", tone = Tone.Danger, dot = true)
                    }
                    diskSubtitle(disk.device, disk.model)?.let { subtitle ->
                        Text(
                            text = subtitle,
                            color = t.muted,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (level == DiskTempLevel.Standby) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        UC.Standby(13.dp, t.muted)
                        Text(
                            text = "Standby",
                            color = t.muted,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        UC.Thermo(13.dp, tempColor)
                        Text(
                            text = "${disk.tempC}°",
                            color = tempColor,
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono),
                        )
                    }
                }
            }
            if (disk.type != DiskType.Parity && !disk.isPoolMember) {
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
            } else if (disk.isPoolMember) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Pool", tone = Tone.Info)
                    Text(
                        text = formatTb(disk.sizeTb),
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
