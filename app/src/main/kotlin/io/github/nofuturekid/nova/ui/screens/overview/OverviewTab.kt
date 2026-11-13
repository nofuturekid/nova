package io.github.nofuturekid.nova.ui.screens.overview

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.AttentionItem
import io.github.nofuturekid.nova.data.model.AttentionSnapshot
import io.github.nofuturekid.nova.data.model.AttentionTarget
import io.github.nofuturekid.nova.data.model.attentionItems
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.DisplayThresholds
import io.github.nofuturekid.nova.data.model.LiveMetrics
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.ServerInfo
import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.TemperatureUnit
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.model.VmState
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.Sparkline
import io.github.nofuturekid.nova.ui.components.StackBar
import io.github.nofuturekid.nova.ui.components.StackSegment
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.components.UnraidProgress
import io.github.nofuturekid.nova.ui.screens.ErrorState
import io.github.nofuturekid.nova.ui.screens.LoadingState
import io.github.nofuturekid.nova.ui.screens.NoServerState
import io.github.nofuturekid.nova.ui.util.formatBytesPerSec
import io.github.nofuturekid.nova.ui.util.uptimeLabel
import io.github.nofuturekid.nova.ui.screens.demo.DemoNote
import io.github.nofuturekid.nova.ui.theme.JetBrainsMono
import io.github.nofuturekid.nova.ui.theme.UnraidAlpha
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

/**
 * Overview is the only tab that subscribes to *all* five per-domain streams
 * (info + metrics + array + docker + vms) — every stat card needs a different
 * source. We render progressively: any sub-state that hasn't loaded yet
 * contributes default/empty values to the layout, so the user sees the
 * dashboard fill in as polls complete rather than a single overall spinner.
 */
@Composable
fun OverviewTab(
    infoState: DomainState<ServerInfo>,
    metricsState: DomainState<LiveMetrics>,
    arrayState: DomainState<ArrayInfo>,
    dockerState: DomainState<List<Container>>,
    vmsState: DomainState<List<Vm>>,
    server: Server?,
    onAddServer: () -> Unit,
    networkThroughput: NetworkThroughput? = null,
    temperature: Temperature? = null,
    cpuWarnC: Int? = null,
    cpuCritC: Int? = null,
    notifications: Notifications? = null,
    displayThresholds: DisplayThresholds? = null,
    onOpenAttention: (AttentionTarget) -> Unit = {},
) {
    // Any single NoServer → no server picked.
    if (infoState is DomainState.NoServer || metricsState is DomainState.NoServer ||
        arrayState is DomainState.NoServer || dockerState is DomainState.NoServer ||
        vmsState is DomainState.NoServer) {
        NoServerState(onAdd = onAddServer)
        return
    }

    val info = (infoState as? DomainState.Content<ServerInfo>)?.value
    val metrics = (metricsState as? DomainState.Content<LiveMetrics>)?.value
    val array = (arrayState as? DomainState.Content<ArrayInfo>)?.value
    val containers = (dockerState as? DomainState.Content<List<Container>>)?.value
    val vms = (vmsState as? DomainState.Content<List<Vm>>)?.value

    // Nothing yet → either Loading or surface the first Error.
    if (info == null && metrics == null && array == null && containers == null && vms == null) {
        val err = listOfNotNull(
            infoState as? DomainState.Error,
            metricsState as? DomainState.Error,
            arrayState as? DomainState.Error,
            dockerState as? DomainState.Error,
            vmsState as? DomainState.Error,
        ).firstOrNull()
        if (err != null) ErrorState(err.message, detail = err.detail) else LoadingState()
        return
    }

    // Same rule as the top bar's Offline pill: an Error on any of these streams
    // means the server is not reachable, so there is nothing trustworthy to flag.
    val offline = listOf(infoState, arrayState, dockerState, vmsState).any { it is DomainState.Error }
    val attention = remember(array, notifications, displayThresholds, offline) {
        if (offline) emptyList()
        else attentionItems(AttentionSnapshot(array, notifications, displayThresholds))
    }

    OverviewContent(
        info, metrics, array, containers, vms, server, networkThroughput, temperature, cpuWarnC, cpuCritC,
        attention, onOpenAttention,
    )
}

@Composable
private fun OverviewContent(
    info: ServerInfo?,
    metrics: LiveMetrics?,
    array: ArrayInfo?,
    containers: List<Container>?,
    vms: List<Vm>?,
    server: Server?,
    networkThroughput: NetworkThroughput? = null,
    temperature: Temperature? = null,
    cpuWarnC: Int? = null,
    cpuCritC: Int? = null,
    attention: List<AttentionItem> = emptyList(),
    onOpenAttention: (AttentionTarget) -> Unit = {},
) {
    val t = UnraidTheme.colors

    // Values that are not known (offline, not loaded yet) stay null all the
    // way to the card, which shows the placeholder; they are never turned
    // into 0 here (#303).
    val cpuPercent = metrics?.cpuPercent
    // Prefer the OS-reported total (metrics) — falls back to the sum of
    // hardware DIMM slots from info when metrics hasn't loaded yet or
    // the server returns zero (some virtualised setups do).
    val memTotalGb = (metrics?.memTotalGb?.takeIf { it > 0.0 })
        ?: info?.memTotalGb
    val memory = memoryFigures(metrics?.memUsedGb, metrics?.memBuffGb, memTotalGb)

    // Rolling sparkline buffers — extend from the polled value each tick.
    // A missing sample empties the buffer instead of adding a fake 0.
    val cpuSeries = remember { mutableStateOf(emptyList<Float>()) }
    val ramSeries = remember { mutableStateOf(emptyList<Float>()) }
    val netSeries = remember { mutableStateOf(emptyList<Float>()) }
    val tempCpuSeries = remember { mutableStateOf(emptyList<Float>()) }
    val tempSysSeries = remember { mutableStateOf(emptyList<Float>()) }

    // Key on metrics only: a 60 s info poll can change memTotalGb without a
    // new metrics sample — keying on it too injected a spurious sparkline tick.
    LaunchedEffect(metrics) {
        cpuSeries.value = rollSeries(cpuSeries.value, cpuPercent?.toFloat())
        ramSeries.value = rollSeries(ramSeries.value, memory.percent)
    }
    LaunchedEffect(networkThroughput) {
        val total = networkThroughput?.let { (it.rxBytesPerSec + it.txBytesPerSec).toFloat() }
        netSeries.value = rollSeries(netSeries.value, total)
    }
    LaunchedEffect(temperature) {
        val live = temperature?.takeIf { it.available }
        tempCpuSeries.value = rollSeries(tempCpuSeries.value, live?.cpuC?.toFloat())
        tempSysSeries.value = rollSeries(tempSysSeries.value, live?.systemC?.toFloat())
    }

    val arrayFig = arrayFigures(array)
    val arrState = array?.state ?: ArrayState.Offline
    val running = containers?.count { it.status == ContainerStatus.Running } ?: 0
    val stopped = containers?.count { it.status == ContainerStatus.Exited } ?: 0
    val paused  = containers?.count { it.status == ContainerStatus.Paused } ?: 0
    val vmRunning = vms?.count { it.state == VmState.Running } ?: 0
    val vmCount = vms?.size ?: 0

    val arrTone = when (arrState) {
        ArrayState.Started -> Tone.Accent
        ArrayState.Parity  -> Tone.Info
        ArrayState.Error   -> Tone.Danger
        else               -> Tone.Neutral
    }
    val arrLabel = when (arrState) {
        ArrayState.Started -> "STARTED"
        ArrayState.Stopped -> "STOPPED"
        ArrayState.Parity  -> "PARITY"
        ArrayState.Error   -> "ERROR"
        ArrayState.Offline -> "OFFLINE"
    }

    val d = UnraidTheme.tokens
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = d.screenPad, end = d.screenPad, top = 4.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(d.gap),
    ) {
        if (server?.isDemo == true) {
            item { DemoNote() }
        }

        // Absent when empty: LazyColumn leaves no gap for an item that draws nothing.
        if (attention.isNotEmpty()) {
            item { AttentionCard(attention, onOpenAttention) }
        }

        item {
            UnraidCard(padding = UnraidTheme.tokens.padHero) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UC.Shield(18.dp, t.muted)
                        Spacer(Modifier.width(8.dp))
                        Text("Array", color = t.muted, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Pill(arrLabel, tone = arrTone, dot = true)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = arrayFig.usedText,
                            color = t.text,
                            style = MaterialTheme.typography.displayLarge,
                        )
                        Text(
                            text = arrayFig.totalText,
                            color = t.muted,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // No segments when the total is unknown: the bar shows only its track.
                    StackBar(
                        segments = arrayFig.barWeights?.let { (used, free) ->
                            listOf(
                                StackSegment(used, t.accent),
                                StackSegment(free, t.muted.copy(alpha = UnraidAlpha.track)),
                            )
                        }.orEmpty(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(arrayFig.percentText, color = t.muted, style = MaterialTheme.typography.bodySmall)
                        Text(
                            arrayFig.freeText,
                            color = t.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    array?.parity?.let { p ->
                        Spacer(Modifier.height(14.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(t.info.copy(alpha = UnraidAlpha.softFill), RoundedCornerShape(10.dp))
                                .border(1.dp, t.info.copy(alpha = UnraidAlpha.softBorder), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row {
                                Text(if (p.paused) "Parity operation paused" else "Parity operation running", color = t.info, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${(p.progress * 100).toInt()}% · ${"%.0f".format(p.speedMbps)} MB/s",
                                    color = t.info,
                                    style = MaterialTheme.typography.labelMedium,
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
                value = cpuValue(cpuPercent),
                sub = cpuSub(
                    cores = info?.cpuCores,
                    brandTail = info?.cpuBrand?.split(" ")?.takeLast(2)?.joinToString(" "),
                    loadKnown = cpuPercent != null,
                ),
                series = cpuSeries.value,
                seriesColor = if ((cpuPercent ?: 0.0) > 80) t.warn else t.accent,
                max = 100f,
            )
        }
        item {
            StatCard(
                iconColor = t.muted,
                icon = { UC.Ram(18.dp, t.muted) },
                label = "Memory",
                value = memory.value,
                sub = memory.sub,
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
                value = networkThroughput
                    ?.let { formatBytesPerSec(it.rxBytesPerSec + it.txBytesPerSec) }
                    ?: "—",
                sub = networkThroughput
                    ?.let { "↓ ${formatBytesPerSec(it.rxBytesPerSec)}  ·  ↑ ${formatBytesPerSec(it.txBytesPerSec)}" }
                    ?: "live unavailable",
                series = netSeries.value,
                seriesColor = Color(0xFFA78BFA),
                max = null,
            )
        }
        item {
            val temp = temperature?.takeIf { it.available }
            // Accent is CPU-driven: prefer global display thresholds (cpuWarnC/cpuCritC)
            // from dynamix display settings when available; fall back to the sensor-derived
            // status flags (temp.cpuCritical / temp.cpuWarning) when not yet loaded.
            val cpuColor = when {
                temp == null -> t.muted
                cpuCritC != null && temp.cpuC != null && temp.cpuC >= cpuCritC -> t.danger
                cpuWarnC != null && temp.cpuC != null && temp.cpuC >= cpuWarnC -> t.warn
                cpuCritC == null && cpuWarnC == null && temp.cpuCritical -> t.danger
                cpuCritC == null && cpuWarnC == null && temp.cpuWarning  -> t.warn
                else -> t.accent
            }
            // System line: a distinct M3-consistent secondary (info/blue) so the
            // two lines read apart on the shared scale (Rule 13).
            val sysColor = t.info
            StatCard(
                iconColor = t.muted,
                icon = { UC.Thermo(18.dp, t.muted) },
                label = "Temperature",
                // Headline = CPU (the meaningful number); fall back to system
                // when a box reports no CPU sensor at all.
                value = temp?.let {
                    val headline = it.cpuC ?: it.systemC
                    headline?.let { v -> "%.0f%s".format(v, unitSymbol(it.unit)) } ?: "—"
                } ?: "—",
                sub = temp?.let {
                    listOfNotNull(
                        it.cpuC?.let { v -> "CPU ${"%.0f".format(v)}${unitSymbol(it.unit)}" },
                        it.systemC?.let { v -> "System ${"%.0f".format(v)}${unitSymbol(it.unit)}" },
                    ).joinToString("  ·  ").ifEmpty { "all normal" }
                } ?: "live unavailable",
                series = tempCpuSeries.value,
                seriesColor = cpuColor,
                max = null,
                series2 = tempSysSeries.value,
                seriesColor2 = sysColor,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UnraidCard(modifier = Modifier.weight(1f), padding = UnraidTheme.tokens.pad) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UC.Docker(16.dp, t.muted)
                            Spacer(Modifier.width(8.dp))
                            Text("Containers", color = t.muted, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("$running", color = t.text, style = MaterialTheme.typography.headlineMedium)
                            Text("running", color = t.muted, style = MaterialTheme.typography.bodySmall)
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
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                UnraidCard(modifier = Modifier.weight(1f), padding = UnraidTheme.tokens.pad) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UC.Vm(16.dp, t.muted)
                            Spacer(Modifier.width(8.dp))
                            Text("Virtual machines", color = t.muted, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("$vmRunning", color = t.text, style = MaterialTheme.typography.headlineMedium)
                            Text("running", color = t.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("$vmCount configured", color = t.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            UnraidCard(padding = UnraidTheme.tokens.pad) {
                Column {
                    Text(
                        text = "SYSTEM",
                        color = t.muted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    InfoRow(icon = { UC.Server(15.dp, t.muted) }, label = "Hostname", value = server?.hostname ?: info?.hostname.orEmpty())
                    InfoRow(icon = { UC.Power(15.dp, t.muted) },  label = "Uptime",   value = uptimeLabel(info?.uptime))
                    InfoRow(icon = { UC.Info(15.dp, t.muted) },   label = "Unraid",   value = info?.unraidVersion.orEmpty())
                    InfoRow(icon = { UC.Terminal(15.dp, t.muted) }, label = "Kernel", value = info?.kernel.orEmpty(), last = true)
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
    series2: List<Float>? = null,
    seriesColor2: Color? = null,
) {
    val t = UnraidTheme.colors
    UnraidCard(padding = UnraidTheme.tokens.pad) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(label, color = t.muted, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    text = value,
                    color = t.text,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Sparkline(
                data = series,
                color = seriesColor,
                height = 56.dp,
                maxValue = max,
                data2 = series2,
                color2 = seriesColor2,
            )
            Text(sub, color = t.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun unitSymbol(unit: TemperatureUnit): String = when (unit) {
    TemperatureUnit.Celsius    -> "°C"
    TemperatureUnit.Fahrenheit -> "°F"
    TemperatureUnit.Kelvin     -> "K"
    TemperatureUnit.Rankine    -> "°R"
    TemperatureUnit.Unknown    -> ""
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
            Text(label, color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, color = t.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono))
        }
        if (!last) HorizontalDivider(color = t.border)
    }
}
