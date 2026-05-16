package net.unraidcontrol.app.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.data.model.NotifImportance
import net.unraidcontrol.app.data.model.NotifTransport
import net.unraidcontrol.app.data.model.Notifications
import net.unraidcontrol.app.data.model.UnraidNotification
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.theme.UnraidTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(
    data: Notifications,
    onDismiss: () -> Unit,
) {
    val t = UnraidTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                        .background(t.muted.copy(alpha = 0.40f)),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Notifications", color = t.text, style = MaterialTheme.typography.titleLarge)
                        // Phase E pilot diagnostic (ADR-0026 E1): which
                        // transport is feeding this. "live" = WS
                        // subscription active; "60s poll" = fallback.
                        when (data.transport) {
                            NotifTransport.Subscription -> Pill("live", tone = Tone.Accent, dot = true)
                            NotifTransport.Poll         -> Pill("60s poll", tone = Tone.Neutral, dot = true)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${data.unreadAlert} alerts · ${data.unreadWarning} warnings",
                        color = t.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Phase E pilot diagnostic: on the poll fallback, show
                    // *why* the WS subscription didn't take so it can be
                    // reported. Removed with the pilot.
                    if (data.transport == NotifTransport.Poll && data.wsError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "WS: ${data.wsError}",
                            color = t.warn,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss)
            }
            Spacer(Modifier.height(18.dp))

            if (data.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No unread warnings or alerts.",
                        color = t.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(data.items, key = { it.id }) { n -> NotificationRow(n) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun NotificationRow(n: UnraidNotification) {
    val t = UnraidTheme.colors
    val accent = when (n.importance) {
        NotifImportance.Alert   -> t.danger
        NotifImportance.Warning -> t.warn
        NotifImportance.Info    -> t.info
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.08f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(n.title, color = t.text, style = MaterialTheme.typography.labelLarge)
            if (n.subject.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(n.subject, color = t.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (n.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    n.description,
                    color = t.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            n.timestamp?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = t.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
