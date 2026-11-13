package io.github.nofuturekid.nova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nofuturekid.nova.data.model.Access
import io.github.nofuturekid.nova.data.model.KeyAction
import io.github.nofuturekid.nova.ui.theme.UnraidAlpha
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

/** The README section that says which permission each feature needs (#316). */
const val PERMISSIONS_HELP_URL = "https://github.com/hellwand/nova#api-key-permissions"

/**
 * The two lines of a "View only" note (#315): what the key cannot do, and the permission it would
 * need. Pure, so a test can pin every wording; the composable below only draws it.
 */
data class ViewOnly(val title: String, val needs: String) {
    companion object {
        val Containers = ViewOnly(
            "View only. This API key can't start, stop or update containers.",
            "Needs ${KeyAction.ControlDocker.permission}",
        )
        val Vms = ViewOnly(
            "View only. This API key can't start or stop virtual machines.",
            "Needs ${KeyAction.ControlVms.permission}",
        )
        val Array = ViewOnly(
            "View only. This API key can't start or stop the array or run parity checks.",
            "Needs ${KeyAction.ControlArray.permission}",
        )

        /**
         * The note for the notifications sheet, or null when the key may do both. Archiving needs
         * `NOTIFICATIONS:UPDATE_ANY`, deleting `NOTIFICATIONS:DELETE_ANY`; only a [Access.Denied]
         * counts, so unknown access shows no note and keeps the buttons.
         */
        fun notifications(update: Access, delete: Access): ViewOnly? {
            val noUpdate = update == Access.Denied
            val noDelete = delete == Access.Denied
            val upd = KeyAction.UpdateNotifications.permission
            val del = KeyAction.DeleteNotifications.permission
            return when {
                noUpdate && noDelete -> ViewOnly(
                    "View only. This API key can't archive or delete notifications.",
                    "Needs $upd and $del",
                )
                noDelete -> ViewOnly("This API key can't delete notifications.", "Needs $del")
                noUpdate -> ViewOnly("This API key can't archive notifications.", "Needs $upd")
                else -> null
            }
        }
    }
}

/**
 * One note per surface where a key's missing permission hides the controls: it sits where they
 * were and says why. Both lines are one merged node, so a screen reader reads them together;
 * the "Which permissions?" link sits outside that node, so the note itself is not a button.
 */
@Composable
fun ViewOnlyNote(note: ViewOnly, modifier: Modifier = Modifier) {
    val t = UnraidTheme.colors
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.muted.copy(alpha = UnraidAlpha.softFill), RoundedCornerShape(12.dp))
                .border(1.dp, t.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UC.Lock(18.dp, t.muted)
            Column(modifier = Modifier.weight(1f)) {
                Text(note.title, color = t.text, style = MaterialTheme.typography.bodyMedium)
                Text(note.needs, color = t.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        UnraidButton(
            onClick = { runCatching { uriHandler.openUri(PERMISSIONS_HELP_URL) } },
            label = "Which permissions?",
            variant = BtnVariant.Text,
            tone = Tone.Accent,
        )
    }
}
