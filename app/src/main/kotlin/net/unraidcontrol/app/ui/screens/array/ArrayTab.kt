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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onAddServer: () -> Unit,
    onStartArray: () -> Unit,
    onStopArray: () -> Unit,
) {
    when (state) {
        DomainState.Loading    -> LoadingState()
        DomainState.NoServer   -> NoServerState(onAdd = onAddServer)
        is DomainState.Error   -> ErrorState(state.message)
        is DomainState.Content -> ArrayContent(state.value, onStartArray, onStopArray)
    }
}

@Composable
private fun ArrayContent(
    arr: ArrayInfo,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val t = UnraidTheme.colors
    val arrayOn = arr.state != ArrayState.Stopped && arr.state != ArrayState.Offline
    val parity  = arr.state == ArrayState.Parity
    val errored = arr.state == ArrayState.Error
    val dataDisks = arr.disks.filter { it.type == DiskType.Data }
    val totalUsedTb = dataDisks.sumOf { it.usedTb }
    val totalSizeTb = dataDisks.sumOf { it.sizeTb }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            UnraidCard(padding = 18.dp) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                UC.Shield(18.dp, t.muted)
                                Text("Array status", color = t.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.5).sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${arr.disks.size} disks · ${"%.1f".format(totalUsedTb)} / ${"%.0f".format(totalSizeTb)} TB",
                                color = t.muted,
                                fontSize = 12.sp,
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
                    if (parity && arr.parity != null) {
                        Spacer(Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(t.info.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .border(1.dp, t.info.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Row {
                                Text("Parity check in progress", color = t.info, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${(arr.parity.progress * 100).toInt()}%",
                                    color = t.info,
                                    fontSize = 13.sp,
                                    fontFamily = JetBrainsMono,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            UnraidProgress(arr.parity.progress, color = t.info, height = 6.dp)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Text(
                                    "${"%.0f".format(arr.parity.speedMbps)} MB/s · ${arr.parity.errors} errors",
                                    color = t.muted,
                                    fontSize = 11.sp,
                                )
                                Spacer(Modifier.weight(1f))
                                val eta = formatEta(arr.parity.etaSeconds)
                                if (eta.isNotEmpty()) Text("~$eta remaining", color = t.muted, fontSize = 11.sp)
                            }
                        }
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
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Read errors detected. Investigate before next array operation.",
                                    color = t.muted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { SectionLabel("Disks") }

        items(arr.disks, key = { it.name }) { disk ->
            DiskCard(disk, errored = errored && disk.status == net.unraidcontrol.app.data.model.DiskStatus.Error)
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
    UnraidCard(padding = 14.dp) {
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
                        Text(disk.name, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (errored) Pill("ERR", tone = Tone.Danger, dot = true)
                    }
                    Text(
                        text = "${disk.device} · ${disk.model}",
                        color = t.muted,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    UC.Thermo(13.dp, tempColor)
                    Text(
                        text = "${disk.tempC}°",
                        color = tempColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = JetBrainsMono,
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
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMono,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${(pct * 100).toInt()}% · ${formatTb(disk.sizeTb)}",
                        color = t.muted,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMono,
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
