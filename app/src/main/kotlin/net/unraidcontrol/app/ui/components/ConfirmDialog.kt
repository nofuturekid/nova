package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.unraidcontrol.app.ui.theme.UnraidDims
import net.unraidcontrol.app.ui.theme.UnraidTheme

data class ConfirmRequest(
    val title: String,
    val body: String,
    val confirmLabel: String = "Confirm",
    val tone: Tone = Tone.Accent,
    val icon: (@Composable () -> Unit)? = null,
    val onConfirm: () -> Unit,
)

/**
 * Material 3 [AlertDialog] (ADR-0030 P7), signature unchanged.
 *
 * Rule 13 — M3-idiomatic appearance is ACCEPTED here over strict
 * zero-visual: the dialog now uses the standard M3 confirm/dismiss button
 * row, M3 dialog elevation/scrim and M3 title/text typography slots. The
 * theme surface, dialog corner ([UnraidDims.radDialog]) and the tone
 * icon-chip are preserved. Buttons remain [UnraidButton] (Text variant),
 * so their colours route through the P1 helpers (P5); the Cancel button
 * keeps `Tone.Neutral` → now neutral/muted per the P5 decision.
 */
@Composable
fun ConfirmDialog(
    request: ConfirmRequest?,
    onDismiss: () -> Unit,
) {
    if (request == null) return
    val t = UnraidTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(UnraidDims.radDialog),
        containerColor = t.surface2,
        titleContentColor = t.text,
        textContentColor = t.muted,
        icon = request.icon?.let {
            {
                val c = request.tone.colors()
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(c.bg)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) { it() }
            }
        },
        title = {
            Text(
                text = request.title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = request.body,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            )
        },
        confirmButton = {
            UnraidButton(
                onClick = { request.onConfirm() },
                label = request.confirmLabel,
                variant = BtnVariant.Text,
                tone = request.tone,
            )
        },
        dismissButton = {
            UnraidButton(
                onClick = onDismiss,
                label = "Cancel",
                variant = BtnVariant.Text,
                tone = Tone.Neutral,
            )
        },
    )
}

