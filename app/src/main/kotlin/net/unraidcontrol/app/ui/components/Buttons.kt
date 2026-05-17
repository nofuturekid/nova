package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidDims
import net.unraidcontrol.app.ui.theme.UnraidTheme

enum class BtnVariant { Filled, Tonal, Outline, Text }

/** On-accent text: dark on light/bright accents, white on dark ones, so a
 *  Filled button stays legible for every accent + theme (audit P2). */
private fun onToneColor(tone: Color): Color =
    if (tone.luminance() > 0.45f) Color(0xFF06120E) else Color.White

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
        // alpha-dimming the whole button (keeps on-accent text readable).
        fg = t.muted
        when (variant) {
            BtnVariant.Filled, BtnVariant.Tonal -> { bg = t.muted.copy(alpha = UnraidAlpha.disabledFill); showBorder = false }
            BtnVariant.Outline                  -> { bg = Color.Transparent;           showBorder = true  }
            BtnVariant.Text                     -> { bg = Color.Transparent;           showBorder = false }
        }
    } else {
        when (variant) {
            BtnVariant.Filled  -> { bg = toneColor;                       fg = onToneColor(toneColor); showBorder = false }
            BtnVariant.Tonal   -> { bg = toneColor.copy(alpha = UnraidAlpha.tonalFill); fg = toneColor;              showBorder = false }
            BtnVariant.Outline -> { bg = Color.Transparent;               fg = t.text;                 showBorder = true  }
            BtnVariant.Text    -> { bg = Color.Transparent;               fg = toneColor;              showBorder = false }
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val base = modifier
        .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
        .clip(CircleShape)
        .background(bg)
        .let { if (showBorder) it.border(BorderStroke(1.dp, t.border), CircleShape) else it }
        .clickable(
            enabled = enabled,
            onClick = onClick,
            role = Role.Button,
            interactionSource = interaction,
            indication = ripple(color = fg),
        )
        .sizeIn(minHeight = UnraidDims.touchMin)
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

/**
 * Icon-only button. [contentDescription] is REQUIRED — an icon-only
 * control carries no visible label, so without it TalkBack announces an
 * unlabelled "button" (audit P0). The compiler enforces this at every
 * call site.
 *
 * The coloured circle stays [visualSize]; a >=48dp touch target is
 * layered around it (audit P0). Disabled dims only the icon content
 * (M3 0.38 alpha), not the whole control.
 */
@Composable
fun UnraidIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = UnraidDims.iconButtonVisual,
    tone: Tone? = null,
    enabled: Boolean = true,
) {
    val t = UnraidTheme.colors
    val bg: Color = when (tone) {
        Tone.Accent  -> t.accentDim
        Tone.Danger  -> t.danger.copy(alpha = UnraidAlpha.tonalFill)
        Tone.Warn    -> t.warn.copy(alpha = UnraidAlpha.tonalFill)
        Tone.Info    -> t.info.copy(alpha = UnraidAlpha.tonalFill)
        Tone.Neutral -> t.muted.copy(alpha = UnraidAlpha.tonalFill)
        null         -> Color.Transparent
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .sizeIn(minWidth = UnraidDims.touchMin, minHeight = UnraidDims.touchMin)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                onClickLabel = contentDescription,
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = UnraidDims.touchMin / 2),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .alpha(if (enabled) 1f else 0.38f),
            contentAlignment = Alignment.Center,
        ) { icon() }
    }
}
