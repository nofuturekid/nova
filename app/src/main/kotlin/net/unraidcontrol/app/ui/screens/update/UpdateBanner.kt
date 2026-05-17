package net.unraidcontrol.app.ui.screens.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.data.model.UpdateInfo
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun UpdateBanner(
    info: UpdateInfo,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = UnraidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            // Stronger than accentDim + an accent outline so the update
            // banner actually stands out on the dark dashboard.
            .background(t.accent.copy(alpha = 0.20f))
            .border(1.dp, t.accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UC.Refresh(18.dp, t.accent)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Update available",
                    color = t.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (info.isPrerelease) Pill("BETA", tone = Tone.Warn)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "v${info.version} · tap to install",
                color = t.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        UnraidIconButton(
            icon = { UC.X(16.dp, t.muted) },
            onClick = onDismiss,
            size = 32.dp,
            contentDescription = "Dismiss update",
        )
    }
}
