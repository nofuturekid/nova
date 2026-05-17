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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.data.model.NotifImportance
import net.unraidcontrol.app.data.model.NotifType
import net.unraidcontrol.app.data.model.Notifications
import net.unraidcontrol.app.data.model.UnraidNotification
import net.unraidcontrol.app.data.repository.DomainState
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidTheme

/**
 * Two-segment notifications sheet over the live API's `list(filter)`.
 *
 * - Unread tab: full unread list (incl. INFO). Per-row Archive (= mark
 *   read) + Delete, plus a bulk "Archive all".
 * - Archived tab: per-row Unarchive (restore to unread) + Delete.
 *
 * State envelope is the live [DomainState] so Loading / Error surface like
 * every other domain. Actions are action→refetch (no optimistic local
 * mutation): the repository nudges the notifications stream after the
 * mutation + a server `recalculateOverview`, so this sheet and the bell
 * badge converge on the server's truth. Delete routes through the caller's
 * shared confirm dialog ([onDeleteRequest]) — consistent with the rest of
 * the app's destructive actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(
    state: DomainState<Notifications>,
    onDismiss: () -> Unit,
    onArchive: (String) -> Unit,
    onUnread: (String) -> Unit,
    onArchiveAll: () -> Unit,
    onDeleteRequest: (UnraidNotification) -> Unit,
) {
    val t = UnraidTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val data = (state as? DomainState.Content)?.value
    var tabIndex by remember { mutableIntStateOf(0) }

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
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Notifications", color = t.text, style = MaterialTheme.typography.titleLarge)
                    if (data != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${data.unreadAlert} alerts · ${data.unreadWarning} warnings · ${data.unreadInfo} info",
                            color = t.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss, contentDescription = "Close")
            }
            Spacer(Modifier.height(14.dp))

            // ── Segments ──
            val unread = data?.unread.orEmpty()
            val archived = data?.archived.orEmpty()
            androidx.compose.material3.TabRow(
                selectedTabIndex = tabIndex,
                containerColor = t.surface2,
                contentColor = t.text,
            ) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("Unread (${unread.size})") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("Archived (${archived.size})") },
                )
            }
            Spacer(Modifier.height(12.dp))

            when {
                state is DomainState.Loading -> CenterMsg("Loading notifications…", loading = true)
                state is DomainState.Error ->
                    CenterMsg((state).message.ifBlank { "Couldn't load notifications." })
                state is DomainState.NoServer -> CenterMsg("No server selected.")
                else -> {
                    val list = if (tabIndex == 0) unread else archived
                    if (tabIndex == 0 && unread.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            UnraidButton(
                                onClick = onArchiveAll,
                                label = "Archive all",
                                variant = BtnVariant.Text,
                                tone = Tone.Accent,
                                leadingIcon = { UC.Check(18.dp, t.accent) },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (list.isEmpty()) {
                        CenterMsg(
                            if (tabIndex == 0) "No unread notifications."
                            else "Nothing archived.",
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(list, key = { it.id }) { n ->
                                NotificationRow(
                                    n = n,
                                    archivedTab = tabIndex == 1,
                                    onArchive = { onArchive(n.id) },
                                    onUnread = { onUnread(n.id) },
                                    onDelete = { onDeleteRequest(n) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CenterMsg(
    text: String,
    loading: Boolean = false,
) {
    val t = UnraidTheme.colors
    Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = t.accent)
        } else {
            Text(text, color = t.muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NotificationRow(
    n: UnraidNotification,
    archivedTab: Boolean,
    onArchive: () -> Unit,
    onUnread: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = UnraidTheme.colors
    val accent = when (n.importance) {
        NotifImportance.Alert   -> t.danger
        NotifImportance.Warning -> t.warn
        NotifImportance.Info    -> t.info
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = UnraidAlpha.softFill))
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                (n.formattedTimestamp ?: n.timestamp)?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = t.muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (archivedTab) {
                UnraidIconButton(
                    icon = { UC.Restart(20.dp, t.text) },
                    onClick = onUnread,
                    contentDescription = "Unarchive (restore to unread)",
                )
            } else {
                UnraidIconButton(
                    icon = { UC.Check(20.dp, t.text) },
                    onClick = onArchive,
                    contentDescription = "Archive (mark read)",
                )
            }
            UnraidIconButton(
                icon = { UC.Trash(20.dp, t.danger) },
                onClick = onDelete,
                contentDescription = "Delete notification",
            )
        }
    }
}
