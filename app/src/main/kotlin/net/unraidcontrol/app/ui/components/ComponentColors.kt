package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidTheme

/**
 * Material 3 `*Defaults.colors()` derived from [UnraidColors] (ADR-0030 P1).
 *
 * Foundation only: nothing consumes these yet. P2+ swaps each bespoke
 * component for its real M3 counterpart and passes the matching helper
 * here, so the swap is a structural change with **zero** colour change —
 * the palette decision is made once, now, against the live theme.
 *
 * Each helper mirrors the exact mapping its bespoke component currently
 * hand-rolls (see [UnraidButton], [UnraidIconButton], [UnraidCard],
 * [UnraidField]); if a bespoke mapping changes, its helper must move with
 * it or the future swap stops being zero-visual.
 */

/** On-tone label colour: dark on bright accents, white on dark ones.
 *  Single source of truth (ADR-0030 P3) — [UnraidButton] consumes this;
 *  its former private `onToneColor` duplicate was folded in here. */
internal fun onTone(tone: Color): Color =
    if (tone.luminance() > 0.45f) Color(0xFF06120E) else Color.White

@Composable
private fun Tone.base(): Color {
    val t = UnraidTheme.colors
    return when (this) {
        Tone.Danger  -> t.danger
        Tone.Warn    -> t.warn
        Tone.Info    -> t.info
        Tone.Accent  -> t.accent
        Tone.Neutral -> t.muted
    }
}

// --- Card (P2) -----------------------------------------------------------

@Composable
fun unraidCardColors(container: Color? = null): CardColors {
    val t = UnraidTheme.colors
    return CardDefaults.cardColors(
        containerColor = container ?: t.surface,
        contentColor = t.text,
    )
}

@Composable
fun unraidCardBorder(color: Color? = null): BorderStroke =
    BorderStroke(1.dp, color ?: UnraidTheme.colors.border)

// --- Button family (P5 / P3) --------------------------------------------

@Composable
fun unraidFilledButtonColors(tone: Tone = Tone.Accent): ButtonColors {
    val t = UnraidTheme.colors
    val c = tone.base()
    return ButtonDefaults.buttonColors(
        containerColor = c,
        contentColor = onTone(c),
        disabledContainerColor = t.muted.copy(alpha = UnraidAlpha.disabledFill),
        disabledContentColor = t.muted,
    )
}

@Composable
fun unraidTonalButtonColors(tone: Tone = Tone.Accent): ButtonColors {
    val t = UnraidTheme.colors
    val c = tone.base()
    return ButtonDefaults.filledTonalButtonColors(
        containerColor = c.copy(alpha = UnraidAlpha.tonalFill),
        contentColor = c,
        disabledContainerColor = t.muted.copy(alpha = UnraidAlpha.disabledFill),
        disabledContentColor = t.muted,
    )
}

@Composable
fun unraidOutlinedButtonColors(): ButtonColors {
    val t = UnraidTheme.colors
    return ButtonDefaults.outlinedButtonColors(
        contentColor = t.text,
        disabledContentColor = t.muted,
    )
}

@Composable
fun unraidTextButtonColors(tone: Tone = Tone.Accent): ButtonColors {
    val t = UnraidTheme.colors
    return ButtonDefaults.textButtonColors(
        contentColor = tone.base(),
        disabledContentColor = t.muted,
    )
}

// --- Icon button (P3) ---------------------------------------------------

/** Tonal icon-button: coloured container at [UnraidAlpha.tonalFill],
 *  matching every [UnraidIconButton] tone after P1 harmonisation
 *  (accent already used `accentDim` == tonalFill). */
@Composable
fun unraidTonalIconButtonColors(tone: Tone): IconButtonColors {
    val c = tone.base()
    return IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = c.copy(alpha = UnraidAlpha.tonalFill),
        contentColor = c,
    )
}

/** Untinted icon-button (bespoke `tone = null`): transparent container. */
@Composable
fun unraidPlainIconButtonColors(): IconButtonColors {
    val t = UnraidTheme.colors
    return IconButtonDefaults.iconButtonColors(
        containerColor = Color.Transparent,
        contentColor = t.text,
    )
}

// --- Linear progress (P4) -----------------------------------------------

/** Resolved (indicator, track) pair for [UnraidProgress] → M3
 *  `LinearProgressIndicator`. Mirrors the bespoke mapping exactly:
 *  indicator defaults to `accent`, track to `muted @ UnraidAlpha.track`;
 *  an explicit override (bespoke `color`/`track` param) wins. */
@Composable
fun unraidProgressColors(
    color: Color? = null,
    track: Color? = null,
): Pair<Color, Color> {
    val t = UnraidTheme.colors
    return (color ?: t.accent) to (track ?: t.muted.copy(alpha = UnraidAlpha.track))
}

// --- Switch (P-follow-up F3) --------------------------------------------

/** M3 `Switch` colours for the Settings toggle. Mirrors the bespoke
 *  `Toggle`'s palette via the live theme: checked track = `accent` with
 *  the contrast-correct on-accent thumb ([onTone]); unchecked track =
 *  `text @ UnraidAlpha.controlTrackOff` (the former off-track token). No
 *  hardcoded literals — same single-source-of-truth rule as P1/P3. */
@Composable
fun unraidSwitchColors(): SwitchColors {
    val t = UnraidTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = onTone(t.accent),
        checkedTrackColor = t.accent,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = t.text,
        uncheckedTrackColor = t.text.copy(alpha = UnraidAlpha.controlTrackOff),
        uncheckedBorderColor = Color.Transparent,
    )
}

// --- Text field (P7) ----------------------------------------------------

@Composable
fun unraidTextFieldColors(): TextFieldColors {
    val t = UnraidTheme.colors
    val container = t.muted.copy(alpha = UnraidAlpha.softFill)
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = t.text,
        unfocusedTextColor = t.text,
        focusedContainerColor = container,
        unfocusedContainerColor = container,
        errorContainerColor = container,
        cursorColor = t.accent,
        focusedBorderColor = t.accent,
        unfocusedBorderColor = t.border,
        errorBorderColor = t.danger,
        focusedLabelColor = t.accent,
        unfocusedLabelColor = t.muted,
        errorLabelColor = t.danger,
        focusedPlaceholderColor = t.muted.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = t.muted.copy(alpha = 0.6f),
    )
}
