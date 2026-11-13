package io.github.nofuturekid.nova.ui.screens.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nofuturekid.nova.data.model.AttentionItem
import io.github.nofuturekid.nova.data.model.AttentionSeverity
import io.github.nofuturekid.nova.data.model.AttentionTarget
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

private fun AttentionSeverity.tone(): Tone = when (this) {
    AttentionSeverity.Critical -> Tone.Danger
    AttentionSeverity.Warning  -> Tone.Warn
    AttentionSeverity.Info     -> Tone.Info
}

private fun AttentionSeverity.label(): String = when (this) {
    AttentionSeverity.Critical -> "Critical"
    AttentionSeverity.Warning  -> "Warning"
    AttentionSeverity.Info     -> "Info"
}

private fun AttentionTarget.openLabel(): String = when (this) {
    AttentionTarget.Array         -> "Open the Array tab"
    AttentionTarget.Notifications -> "Open notifications"
}

/**
 * Lists what needs the user's attention, one tappable row per item. Draws
 * nothing for an empty list, so a healthy or offline server shows no card.
 * The severity is spelled out in a pill as well as coloured, so it does not
 * rely on colour alone; each row reads as one unit to a screen reader.
 */
@Composable
fun AttentionCard(
    items: List<AttentionItem>,
    onOpen: (AttentionTarget) -> Unit,
) {
    if (items.isEmpty()) return
    val t = UnraidTheme.colors
    UnraidCard(padding = UnraidTheme.tokens.pad) {
        Column {
            Row(
                modifier = Modifier.semantics { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UC.Alert(18.dp, t.muted)
                Spacer(Modifier.width(8.dp))
                Text("Needs attention", color = t.muted, style = MaterialTheme.typography.labelLarge)
            }
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = t.border)
                AttentionRow(item, onClick = { onOpen(item.target) })
            }
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, onClick: () -> Unit) {
    val t = UnraidTheme.colors
    val tone = item.severity.tone()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = item.target.openLabel())
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = t.text, style = MaterialTheme.typography.bodyMedium)
            Text(item.detail, color = t.muted, style = MaterialTheme.typography.bodySmall)
        }
        Pill(item.severity.label(), tone = tone, dot = true)
        UC.ChevR(18.dp, t.muted)
    }
}
