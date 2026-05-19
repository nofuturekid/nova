package net.unraidcontrol.app.ui.screens.vms

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
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.data.model.VmState
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.components.onTone
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidTheme

/**
 * VM detail bottom-sheet — opened by tapping a VM tile/card. Mirrors
 * ContainerDetailSheet: tapping a VM must not fire an action directly
 * (that made the Grid tile immediately trigger Force-Stop). All
 * lifecycle actions live here behind explicit buttons. Stop/Reset are
 * wired by the caller to the shared confirm dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmDetailSheet(
    vm: Vm,
    onDismiss: () -> Unit,
    onStart: (Vm) -> Unit,
    onResume: (Vm) -> Unit,
    onPause: (Vm) -> Unit,
    onStop: (Vm) -> Unit,
    onReboot: (Vm) -> Unit,
    onReset: (Vm) -> Unit,
) {
    val t = UnraidTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tone = when (vm.state) {
        VmState.Running -> Tone.Accent
        VmState.Paused  -> Tone.Warn
        VmState.Stopped -> Tone.Neutral
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(vm.name, color = t.text, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Pill(label = vm.state.name.lowercase(), tone = tone, dot = true)
                }
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss, contentDescription = "Close")
            }

            Spacer(Modifier.height(18.dp))
            InfoRow("vCPUs", "${vm.vcpus}")
            InfoRow("Memory", "${vm.memGb} GB")
            InfoRow("GPU", vm.gpu ?: "—")
            Spacer(Modifier.height(18.dp))

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
                            leadingIcon = { UC.Play(14.dp, onTone(t.accent)) },
                        )
                    }
                }
            }
            if (vm.state == VmState.Running) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UnraidButton(
                        onClick = { onReboot(vm) },
                        label = "Reboot",
                        modifier = Modifier.weight(1f),
                        variant = BtnVariant.Tonal,
                        fullWidth = true,
                        leadingIcon = { UC.Restart(14.dp, t.accent) },
                    )
                    UnraidButton(
                        onClick = { onReset(vm) },
                        label = "Reset",
                        modifier = Modifier.weight(1f),
                        variant = BtnVariant.Tonal,
                        tone = Tone.Danger,
                        fullWidth = true,
                        leadingIcon = { UC.Power(14.dp, t.danger) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    val t = UnraidTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = t.text, style = MaterialTheme.typography.labelLarge)
    }
}
