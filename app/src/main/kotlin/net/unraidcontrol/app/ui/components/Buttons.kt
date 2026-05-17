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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidDims
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
    // Tone→colour: kept as the bespoke mapping (Accent/Neutral both →
    // accent for the Button family — see ADR-0030 P3 note). Only the
    // on-accent legibility rule is folded into the single source of
    // truth (`onTone` in ComponentColors.kt) — the duplicated copy the
    // ADR named is removed; the Button structural swap stays P5.
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
            BtnVariant.Filled  -> { bg = toneColor;                       fg = onTone(toneColor); showBorder = false }
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
 * Icon-only button — Material 3 `IconButton` / `FilledTonalIconButton`
 * (ADR-0030 P3), consuming the P1 `ComponentColors` helpers as the single
 * source of truth for tone→colour.
 *
 * [contentDescription] is REQUIRED — an icon-only control carries no
 * visible label, so without it TalkBack announces an unlabelled "button"
 * (audit P0). The compiler enforces this at every call site; it is wired
 * to the control's semantics (contentDescription + onClick label) exactly
 * as the previous `clickable(onClickLabel = …)` did.
 *
 * Zero-visual (device-gated): the coloured container is pinned to [size]
 * (not M3's default 40 dp) and clipped to a circle; a ≥48 dp touch target
 * is layered around it (audit P0). Disabled dims only the icon content to
 * M3's 0.38 alpha, supplied by the P1 helper's IconButton defaults.
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
    // Pin the coloured container to `size` + CircleShape so the M3
    // IconButton renders the bespoke circle, not its 40 dp default.
    val containerModifier = Modifier
        .size(size)
        .clip(CircleShape)
    val semanticsModifier = Modifier.semantics {
        this.contentDescription = contentDescription
        onClick(label = contentDescription, action = null)
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = UnraidDims.touchMin, minHeight = UnraidDims.touchMin),
        contentAlignment = Alignment.Center,
    ) {
        if (tone != null) {
            FilledTonalIconButton(
                onClick = onClick,
                modifier = containerModifier.then(semanticsModifier),
                enabled = enabled,
                shape = CircleShape,
                colors = unraidTonalIconButtonColors(tone),
            ) { icon() }
        } else {
            IconButton(
                onClick = onClick,
                modifier = containerModifier.then(semanticsModifier),
                enabled = enabled,
                colors = unraidPlainIconButtonColors(),
            ) { icon() }
        }
    }
}
