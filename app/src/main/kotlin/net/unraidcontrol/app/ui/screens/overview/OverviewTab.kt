package net.unraidcontrol.app.ui.screens.overview

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.unraidcontrol.app.data.model.ArrayState
import net.unraidcontrol.app.data.model.ContainerStatus
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.data.model.ServerSnapshot
import net.unraidcontrol.app.data.model.VmState
import net.unraidcontrol.app.data.repository.SnapshotState
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Sparkline
import net.unraidcontrol.app.ui.components.StackBar
import net.unraidcontrol.app.ui.components.StackSegment
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidCard
import net.unraidcontrol.app.ui.components.UnraidProgress
import net.unraidcontrol.app.ui.screens.ErrorState
import net.unraidcontrol.app.ui.screens.LoadingState
import net.unraidcontrol.app.ui.screens.NoServerState
import net.unraidcontrol.app.ui.theme.JetBrainsMono
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun OverviewTab(
    snapshot: SnapshotState,
    server: Server?,
    onAddServer: () -> Unit,
) {
    when (snapshot) {
        SnapshotState.Loading -> LoadingState()
        SnapshotState.NoServer -> NoServerState(onAdd = onAddServer)
        is SnapshotState.Error -> ErrorState(snapshot.message)
        is SnapshotState.Content -> OverviewContent(snapshot.snapshot, server)
    }
}

@Composable
private fun OverviewContent(snap: ServerSnapshot, server: Server?) {
    val t = UnraidTheme.colors

    // Rolling sparkline buffers — extend from the polled value each tick.
    val cpuSeries = remember { mutableStateOf(List(40) { snap.info.cpu.percent.toFloat() }) }
    val ramSeries = remember { mutableStateOf(List(40) { ((snap.info.memory.usedGb / snap.info.memory.totalGb) * 100).toFloat() }) }
    val netSeries = remember { mutableStateOf(List(40) { snap.info.network.rxMbps.toFloat() }) }

    LaunchedEffect(snap) {
        cpuSeries.value = cpuSeries.value.drop(1) + snap.info.cpu.percent.toFloat()
        ramSeries.value = ramSeries.value.drop(1) +
            ((snap.info.memory.usedGb / snap.info.memory.totalGb) * 100).toFloat()
        netSeries.value = netSeries.value.drop(1) + snap.info.network.rxMbps.toFloat()
    }

    val arrayPct = (snap.array.usedTb / snap.array.totalTb).coerceIn(0.0, 1.0)
    val running = snap.containers.count { it.status == ContainerStatus.Running }
    val stopped = snap.containers.count { it.status == ContainerStatus.Exited }
    val paused  = snap.containers.count { it.status == ContainerStatus.Paused }
    val vmRunning = snap.vms.count { it.state == VmState.Running }

    val arrTone = when (snap.array.state) {
        ArrayState.Started -> Tone.Accent
        ArrayState.Parity  -> Tone.Info
        ArrayState.Error   -> Tone.Danger
        else               -> Tone.Neutral
    }
    val arrLabel = when (snap.array.state) {
        ArrayState.Started -> "STARTED"
        ArrayState.Stopped -> "STOPPED"
        ArrayState.Parity  -> "PARITY"
        ArrayState.Error   -> "ERROR"
        ArrayState.Offline -> "OFFLINE"
    }

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
                        UC.Shield(18.dp, t.muted)
                        Spacer(Modifier.width(8.dp))
                        Text("Array", color = t.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Pill(arrLabel, tone = arrTone, dot = true)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "%.1f".format(snap.array.usedTb),
                            color = t.text,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.5).sp,
                        )
                        Text(
                            text = "/ ${"%.0f".format(snap.array.totalTb)} TB used",
                            color = t.muted,
                            fontSize = 16.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    StackBar(
                        segments = listOf(
                            StackSegment(snap.array.usedTb.toFloat(), t.accent),
                            StackSegment((snap.array.totalTb - snap.array.usedTb).coerceAtLeast(0.0).toFloat(), Color.White.copy(alpha = 0.07f)),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${(arrayPct * 100).toInt()}% used", color = t.muted, fontSize = 12.sp)
                        Text(
                            "${"%.1f".format(snap.array.totalTb - snap.array.usedTb)} TB free",
                            color = t.muted,
                            fontSize = 12.sp,
                        )
                    }
                    snap.array.parity?.let { p ->
                        Spacer(Modifier.height(14.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(t.info.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                .border(1.dp, t.info.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row {
                                Text("Parity check running", color = t.info, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${(p.progress * 100).toInt()}% · ${"%.0f".format(p.speedMbps)} MB/s",
                                    color = t.info,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            UnraidProgress(p.progress, color = t.info, height = 4.dp)
                        }
                    }
                }
            }
        }

        item {
            StatCard(
                iconColor = t.muted,
                icon = { UC.Cpu(18.dp, t.muted) },
                label = "CPU",
                value = "%.1f%%".format(snap.info.cpu.percent),
                sub = "${snap.info.cpu.cores} cores · ${snap.info.cpu.brand.split(" ").takeLast(2).joinToString(" ")}",
                series = cpuSeries.value,
                seriesColor = if (snap.info.cpu.percent > 80) t.warn else t.accent,
                max = 100f,
            )
        }
        item {
            val pct = if (snap.info.memory.totalGb > 0) (snap.info.memory.usedGb / snap.info.memory.totalGb * 100).toInt() else 0
            StatCard(
                iconColor = t.muted,
                icon = { UC.Ram(18.dp, t.muted) },
                label = "Memory",
                value = "%.1f GB".format(snap.info.memory.usedGb),
                sub = "$pct% of ${"%.0f".format(snap.info.memory.totalGb)} GB · ${"%.1f".format(snap.info.memory.buffersGb)} GB cached",
                series = ramSeries.value,
                seriesColor = t.accent,
                max = 100f,
            )
        }
        item {
            StatCard(
                iconColor = t.muted,
                icon = { UC.Network(18.dp, t.muted) },
                label = "Network",
                value = "%.1f Mbps".format(snap.info.network.rxMbps),
                sub = "↓ ${"%.1f".format(snap.info.network.rxMbps)}  ·  ↑ ${"%.1f".format(snap.info.network.txMbps)} Mbps",
                series = netSeries.value,
                seriesColor = Color(0xFFA78BFA),
                max = null,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UnraidCard(modifier = Modifier.weight(1f), padding = 14.dp) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UC.Docker(16.dp, t.muted)
                            Spacer(Modifier.width(8.dp))
                            Text("Containers", color = t.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("$running", color = t.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text("running", color = t.muted, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when {
                                stopped == 0 && paused == 0 -> "all healthy"
                                else -> listOfNotNull(
                                    if (stopped > 0) "$stopped stopped" else null,
                                    if (paused > 0) "$paused paused" else null,
                                ).joinToString(" · ")
                            },
                            color = t.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
                UnraidCard(modifier = Modifier.weight(1f), padding = 14.dp) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UC.Vm(16.dp, t.muted)
                            Spacer(Modifier.width(8.dp))
                            Text("Virtual machines", color = t.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("$vmRunning", color = t.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text("running", color = t.muted, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${snap.vms.size} configured", color = t.muted, fontSize = 11.sp)
                    }
                }
            }
        }

        item {
            UnraidCard(padding = 16.dp) {
                Column {
                    Text(
                        text = "SYSTEM",
                        color = t.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.3.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    InfoRow(icon = { UC.Server(15.dp, t.muted) }, label = "Hostname", value = server?.hostname ?: snap.info.hostname)
                    InfoRow(icon = { UC.Power(15.dp, t.muted) },  label = "Uptime",   value = snap.info.uptime)
                    InfoRow(icon = { UC.Info(15.dp, t.muted) },   label = "Unraid",   value = snap.info.unraidVersion)
                    InfoRow(icon = { UC.Terminal(15.dp, t.muted) }, label = "Kernel", value = snap.info.kernel, last = true)
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    iconColor: Color,
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    sub: String,
    series: List<Float>,
    seriesColor: Color,
    max: Float?,
) {
    val t = UnraidTheme.colors
    UnraidCard(padding = 16.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(label, color = t.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = value,
                    color = t.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.4).sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Sparkline(
                data = series,
                color = seriesColor,
                height = 56.dp,
                maxValue = max,
            )
            Text(sub, color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    last: Boolean = false,
) {
    val t = UnraidTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Text(label, color = t.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = t.text, fontSize = 13.sp, fontFamily = JetBrainsMono)
        }
        if (!last) HorizontalDivider(color = t.border)
    }
}

