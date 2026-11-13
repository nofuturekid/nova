package io.github.nofuturekid.nova.ui.screens.demo

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.repository.ServerRepository
import io.github.nofuturekid.nova.ui.components.BtnVariant
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidButton
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.components.UnraidIconButton
import io.github.nofuturekid.nova.ui.theme.UnraidAlpha
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The sentence that says what the demo is; shown on the Overview and in the demo entry's sheet. */
const val DEMO_NOTICE = "Demo mode: this server and everything on it is fictional. Nothing is sent over the network."

/**
 * How a screen enters the demo (ADR-0050), or null where it cannot: provided once at the top of
 * the navigation graph, read by the empty state, so the four tabs need no extra parameter.
 */
val LocalDemoEntry = staticCompositionLocalOf<(() -> Unit)?> { null }

@HiltViewModel
class DemoEntryViewModel @Inject constructor(
    private val servers: ServerRepository,
) : ViewModel() {
    fun enter() { viewModelScope.launch { servers.enterDemo() } }
    fun remove() { viewModelScope.launch { servers.delete(DemoFixtures.SERVER_ID) } }
}

/** The one-sentence note at the top of the demo's Overview. */
@Composable
fun DemoNote() {
    val t = UnraidTheme.colors
    UnraidCard(padding = UnraidTheme.tokens.pad) {
        Text(text = DEMO_NOTICE, color = t.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the demo entry's "edit" opens: there is nothing to edit, only to read and to remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoServerSheet(
    onDismiss: () -> Unit,
    onRemoved: () -> Unit,
    vm: DemoEntryViewModel,
) {
    val t = UnraidTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.surface2,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(t.muted.copy(alpha = UnraidAlpha.grabber), RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Demo server",
                    color = t.text,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss, contentDescription = "Close")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$DEMO_NOTICE Changes you make stay in memory only and are gone when you remove it or close the app.",
                color = t.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnraidButton(
                    onClick = { vm.remove(); onRemoved() },
                    label = "Remove demo",
                    variant = BtnVariant.Tonal,
                    tone = Tone.Danger,
                    leadingIcon = { UC.Trash(14.dp, t.danger) },
                )
                Spacer(Modifier.weight(1f))
                UnraidButton(onClick = onDismiss, label = "Close", variant = BtnVariant.Text, tone = Tone.Neutral)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
