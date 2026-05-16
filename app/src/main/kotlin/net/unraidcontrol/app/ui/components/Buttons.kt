package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidTheme

enum class BtnVariant { Filled, Tonal, Outline, Text }

@Composable
fun UnraidButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.Tonal,
    tone: Tone = Tone.Accent,
    fullWidth: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val t = UnraidTheme.colors
    val toneColor: Color = when (tone) {
        Tone.Danger -> t.danger
        Tone.Warn   -> t.warn
        Tone.Info   -> t.info
        else        -> t.accent
    }
    val bg: Color
    val fg: Color
    val showBorder: Boolean
    if (!enabled) {
        // Disabled: derive a theme-correct muted style instead of
        // alpha-dimming the whole button. The old .alpha(0.5f) made
        // Filled's hard-coded dark on-accent text unreadable on the
        // dark sheet surface, and toggling enabled (e.g. Save's
        // canSave) looked like an erratic colour flip.
        fg = t.muted
        when (variant) {
            BtnVariant.Filled, BtnVariant.Tonal -> { bg = t.muted.copy(alpha = 0.14f); showBorder = false }
            BtnVariant.Outline                  -> { bg = Color.Transparent;           showBorder = true  }
            BtnVariant.Text                     -> { bg = Color.Transparent;           showBorder = false }
        }
    } else {
        when (variant) {
            BtnVariant.Filled  -> { bg = toneColor;                       fg = Color(0xFF06120E); showBorder = false }
            BtnVariant.Tonal   -> { bg = toneColor.copy(alpha = 0.16f);   fg = toneColor;          showBorder = false }
            BtnVariant.Outline -> { bg = Color.Transparent;               fg = t.text;             showBorder = true  }
            BtnVariant.Text    -> { bg = Color.Transparent;               fg = toneColor;          showBorder = false }
        }
    }
    val base = modifier
        .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
        .clip(CircleShape)
        .background(bg)
        .let { if (showBorder) it.border(BorderStroke(1.dp, t.border), CircleShape) else it }
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 10.dp)

    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (leadingIcon != null) Box { leadingIcon() }
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun UnraidIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    tone: Tone? = null,
    enabled: Boolean = true,
) {
    val t = UnraidTheme.colors
    val bg: Color = when (tone) {
        Tone.Accent  -> t.accentDim
        Tone.Danger  -> t.danger.copy(alpha = 0.14f)
        Tone.Warn    -> t.warn.copy(alpha = 0.14f)
        Tone.Info    -> t.info.copy(alpha = 0.14f)
        Tone.Neutral -> t.muted.copy(alpha = 0.12f)
        null         -> Color.Transparent
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
