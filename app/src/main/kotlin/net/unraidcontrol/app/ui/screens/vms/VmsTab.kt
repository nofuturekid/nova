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
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.data.model.VmState
import net.unraidcontrol.app.data.repository.DomainState
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.components.UnraidCard
import net.unraidcontrol.app.ui.screens.ErrorState
import net.unraidcontrol.app.ui.screens.LoadingState
import net.unraidcontrol.app.ui.screens.NoServerState
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun VmsTab(
    state: DomainState<List<Vm>>,
    onAddServer: () -> Unit,
    onStart: (Vm) -> Unit,
    onResume: (Vm) -> Unit,
    onPause: (Vm) -> Unit,
    onStop: (Vm) -> Unit,
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
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vms, key = { it.id }) { vm ->
                        VmCard(vm, onStart, onResume, onPause, onStop)
                    }
                }
            }
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
    onStart: (Vm) -> Unit,
    onResume: (Vm) -> Unit,
    onPause: (Vm) -> Unit,
    onStop: (Vm) -> Unit,
) {
    val t = UnraidTheme.colors
    val tone = when (vm.state) {
        VmState.Running -> Tone.Accent
        VmState.Paused  -> Tone.Warn
        VmState.Stopped -> Tone.Neutral
    }
    val stateLabel = vm.state.name.lowercase()
    UnraidCard(padding = 14.dp) {
        Column {
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
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (vm.state) {
                    VmState.Running -> {
                        UnraidButton(
                            onClick = { onPause(vm) },
                            label = "Pause",
                            modifier = Modifier.weight(1f),
                            variant = BtnVariant.Tonal,
                            tone = Tone.Warn,
                            fullWidth = true,
                            leadingIcon = { UC.Pause(14.dp, t.warn) },
                        )
                        UnraidButton(
                            onClick = { onStop(vm) },
                            label = "Stop",
                            modifier = Modifier.weight(1f),
                            variant = BtnVariant.Tonal,
                            tone = Tone.Danger,
                            fullWidth = true,
                            leadingIcon = { UC.Stop(14.dp, t.danger) },
                        )
                    }
                    VmState.Paused -> {
                        UnraidButton(
                            onClick = { onResume(vm) },
                            label = "Resume",
                            modifier = Modifier.weight(1f),
                            variant = BtnVariant.Tonal,
                            fullWidth = true,
                            leadingIcon = { UC.Play(14.dp, t.accent) },
                        )
                        UnraidButton(
                            onClick = { onStop(vm) },
                            label = "Stop",
                            modifier = Modifier.weight(1f),
                            variant = BtnVariant.Tonal,
                            tone = Tone.Danger,
                            fullWidth = true,
                            leadingIcon = { UC.Stop(14.dp, t.danger) },
                        )
                    }
                    VmState.Stopped -> {
                        UnraidButton(
                            onClick = { onStart(vm) },
                            label = "Start",
                            modifier = Modifier.weight(1f),
                            variant = BtnVariant.Filled,
                            fullWidth = true,
                            leadingIcon = { UC.Play(14.dp, Color(0xFF06120E)) },
                        )
                    }
                }
            }
        }
    }
}
