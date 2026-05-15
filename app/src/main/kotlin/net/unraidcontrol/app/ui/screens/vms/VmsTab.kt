package net.unraidcontrol.app.ui.screens.vms

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.unraidcontrol.app.data.local.LayoutMode
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.data.model.VmState
import net.unraidcontrol.app.data.repository.DomainState
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.SectionLabel
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidCard
import net.unraidcontrol.app.ui.screens.ErrorState
import net.unraidcontrol.app.ui.screens.LoadingState
import net.unraidcontrol.app.ui.screens.NoServerState
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun VmsTab(
    state: DomainState<List<Vm>>,
    view: LayoutMode,
    onAddServer: () -> Unit,
    onStart: (Vm) -> Unit,
    onResume: (Vm) -> Unit,
    onPause: (Vm) -> Unit,
    onOpenVm: (Vm) -> Unit,
) {
    when (state) {
        DomainState.Loading    -> LoadingState()
        DomainState.NoServer   -> NoServerState(onAdd = onAddServer)
        is DomainState.Error   -> ErrorState(state.message)
        is DomainState.Content -> {
            val vms = state.value
            if (vms.isEmpty()) {
                EmptyVms()
            } else {
                val d = UnraidTheme.tokens
                val pad = androidx.compose.foundation.layout.PaddingValues(
                    start = d.screenPad, end = d.screenPad, top = 4.dp, bottom = 24.dp,
                )
                when (view) {
                    LayoutMode.List -> LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = pad,
                        verticalArrangement = Arrangement.spacedBy(d.gap),
                    ) {
                        items(vms, key = { it.id }) { vm ->
                            VmCard(vm, onOpenVm, onStart, onResume, onPause)
                        }
                    }
                    LayoutMode.Grid -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = pad,
                        horizontalArrangement = Arrangement.spacedBy(d.gap),
                        verticalArrangement = Arrangement.spacedBy(d.gap),
                    ) {
                        gridItems(vms, key = { it.id }) { vm ->
                            VmTile(vm, onOpenVm)
                        }
                    }
                    LayoutMode.Grouped -> {
                        val running = vms.filter { it.state == VmState.Running }
                        val paused  = vms.filter { it.state == VmState.Paused }
                        val stopped = vms.filter { it.state == VmState.Stopped }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = pad,
                            verticalArrangement = Arrangement.spacedBy(d.gap),
                        ) {
                            if (running.isNotEmpty()) {
                                item { SectionLabel("Running · ${running.size}") }
                                items(running, key = { "r-${it.id}" }) { vm ->
                                    VmCard(vm, onOpenVm, onStart, onResume, onPause)
                                }
                            }
                            if (paused.isNotEmpty()) {
                                item { SectionLabel("Paused · ${paused.size}") }
                                items(paused, key = { "p-${it.id}" }) { vm ->
                                    VmCard(vm, onOpenVm, onStart, onResume, onPause)
                                }
                            }
                            if (stopped.isNotEmpty()) {
                                item { SectionLabel("Stopped · ${stopped.size}") }
                                items(stopped, key = { "s-${it.id}" }) { vm ->
                                    VmCard(vm, onOpenVm, onStart, onResume, onPause)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact VM tile for the Grid layout — icon, name, state pill, tap to start/stop. */
@Composable
private fun VmTile(
    vm: Vm,
    onOpen: (Vm) -> Unit,
) {
    val t = UnraidTheme.colors
    val tone = when (vm.state) {
        VmState.Running -> Tone.Accent
        VmState.Paused  -> Tone.Warn
        VmState.Stopped -> Tone.Neutral
    }
    val dim = vm.state != VmState.Running
    UnraidCard(padding = UnraidTheme.tokens.padTight, onClick = { onOpen(vm) }) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(t.accent, Color(0xFF3B82F6))))
                    .alpha(if (dim) 0.55f else 1f),
                contentAlignment = Alignment.Center,
            ) { UC.Vm(20.dp, Color(0xFF06120E)) }
            Spacer(Modifier.height(8.dp))
            Text(
                text = vm.name,
                color = t.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Pill(vm.state.name.lowercase(), tone = tone, dot = true)
        }
    }
}

@Composable
private fun EmptyVms() {
    val t = UnraidTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(60.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UC.Vm(36.dp, t.muted)
        Spacer(Modifier.height(8.dp))
        Text("No VMs configured", color = t.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "This server has no virtual machines yet. Configure them in the web UI to manage them here.",
            color = t.muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VmCard(
    vm: Vm,
    onOpen: (Vm) -> Unit,
    onStart: (Vm) -> Unit,
    onResume: (Vm) -> Unit,
    onPause: (Vm) -> Unit,
) {
    val t = UnraidTheme.colors
    val tone = when (vm.state) {
        VmState.Running -> Tone.Accent
        VmState.Paused  -> Tone.Warn
        VmState.Stopped -> Tone.Neutral
    }
    val stateLabel = vm.state.name.lowercase()
    // Compact card: identity + spec + one safe primary action (no
    // confirm). Tapping the card opens the detail sheet for everything
    // else (stop/reboot/reset, which go through their confirms there).
    UnraidCard(padding = UnraidTheme.tokens.pad, onClick = { onOpen(vm) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(t.accent, Color(0xFF3B82F6)),
                        ),
                    )
                    .alpha(if (vm.state == VmState.Running) 1f else 0.55f),
                contentAlignment = Alignment.Center,
            ) {
                UC.Vm(20.dp, Color(0xFF06120E))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(vm.name, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(stateLabel, tone = tone, dot = true)
                    Text(
                        text = buildString {
                            append("${vm.vcpus} vCPU · ${vm.memGb} GB")
                            if (vm.gpu != null) append(" · ${vm.gpu}")
                        },
                        color = t.muted,
                        fontSize = 11.sp,
                    )
                }
            }
            when (vm.state) {
                VmState.Running -> UnraidIconButton(
                    icon = { UC.Pause(16.dp, t.warn) },
                    onClick = { onPause(vm) },
                    size = 36.dp,
                    tone = Tone.Warn,
                )
                VmState.Paused -> UnraidIconButton(
                    icon = { UC.Play(16.dp, t.accent) },
                    onClick = { onResume(vm) },
                    size = 36.dp,
                    tone = Tone.Accent,
                )
                VmState.Stopped -> UnraidIconButton(
                    icon = { UC.Play(16.dp, t.accent) },
                    onClick = { onStart(vm) },
                    size = 36.dp,
                    tone = Tone.Accent,
                )
            }
        }
    }
}
