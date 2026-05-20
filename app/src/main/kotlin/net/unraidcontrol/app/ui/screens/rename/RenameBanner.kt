package net.unraidcontrol.app.ui.screens.rename

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun RenameBanner(onDismiss: () -> Unit) {
    val t = UnraidTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(t.warn.copy(alpha = UnraidAlpha.emphasisFill))
            .border(1.dp, t.warn.copy(alpha = UnraidAlpha.emphasisBorder), RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UC.Info(18.dp, t.warn)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "This app is being renamed",
                color = t.warn,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Next release: NOVA for Unraid® — install separately",
                color = t.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        UnraidIconButton(
            icon = { UC.X(16.dp, t.muted) },
            onClick = onDismiss,
            size = 32.dp,
            contentDescription = "Dismiss",
        )
    }
}
