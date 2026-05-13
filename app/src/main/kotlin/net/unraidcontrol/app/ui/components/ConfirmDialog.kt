package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.unraidcontrol.app.ui.theme.UnraidTheme

data class ConfirmRequest(
    val title: String,
    val body: String,
    val confirmLabel: String = "Confirm",
    val tone: Tone = Tone.Accent,
    val icon: (@Composable () -> Unit)? = null,
    val onConfirm: () -> Unit,
)

@Composable
fun ConfirmDialog(
    request: ConfirmRequest?,
    onDismiss: () -> Unit,
) {
    if (request == null) return
    val t = UnraidTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(t.surface2)
                .padding(top = 24.dp, start = 22.dp, end = 22.dp, bottom = 16.dp),
        ) {
            val icon = request.icon
            if (icon != null) {
                val c = request.tone.colors()
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(c.bg),
                    contentAlignment = Alignment.Center,
                ) { icon() }
                Spacer(Modifier.height(14.dp))
            }
            Text(
                text = request.title,
                color = t.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = request.body,
                color = t.muted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                UnraidButton(
                    onClick = onDismiss,
                    label = "Cancel",
                    variant = BtnVariant.Text,
                    tone = Tone.Neutral,
                )
                Spacer(Modifier.width(4.dp))
                UnraidButton(
                    onClick = {
                        request.onConfirm()
                    },
                    label = request.confirmLabel,
                    variant = BtnVariant.Text,
                    tone = request.tone,
                )
            }
        }
    }
}

