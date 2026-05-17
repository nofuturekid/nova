package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidDims
import net.unraidcontrol.app.ui.theme.UnraidTheme

enum class BtnVariant { Filled, Tonal, Outline, Text }

/**
 * Material 3 Button family (ADR-0030 P5), signature unchanged so all 33
 * call sites compile untouched.
 *
 *  - `Filled` → [Button], `Tonal` → [FilledTonalButton],
 *    `Outline` → [OutlinedButton], `Text` → [TextButton];
 *  - pill [CircleShape], `sizeIn(minHeight = UnraidDims.touchMin)`,
 *    `fullWidth`, `leadingIcon`, `enabled` and the `labelLarge` label are
 *    all preserved; content padding is pinned to the former bespoke
 *    18 dp × 10 dp so geometry is unchanged;
 *  - colours come EXCLUSIVELY from the P1 helpers
 *    ([unraidFilledButtonColors] / [unraidTonalButtonColors] /
 *    [unraidOutlinedButtonColors] / [unraidTextButtonColors]); the
 *    file-local `onTone`/luminance hand-rolling is gone.
 *
 * INTENDED visual change (ADR-0030 P3 decision, recorded here): the old
 * `else → accent` lumped [Tone.Accent] and [Tone.Neutral] together — a
 * latent bug. The helpers map `Tone.Neutral → muted`, so the four
 * `Tone.Neutral` Text call sites (Cancel / Later / Close) now render
 * neutral/muted instead of accent-coloured. Everything else is
 * bit-identical (zero-visual). See the ADR-0030 combined acceptance
 * checklist.
 */
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
    val btnModifier = modifier
        .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
        .sizeIn(minHeight = UnraidDims.touchMin)
    val contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)

    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (leadingIcon != null) {
            Box { leadingIcon() }
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }

    when (variant) {
        BtnVariant.Filled -> Button(
            onClick = onClick,
            modifier = btnModifier,
            enabled = enabled,
            shape = CircleShape,
            colors = unraidFilledButtonColors(tone),
            contentPadding = contentPadding,
            content = content,
        )
        BtnVariant.Tonal -> FilledTonalButton(
            onClick = onClick,
            modifier = btnModifier,
            enabled = enabled,
            shape = CircleShape,
            colors = unraidTonalButtonColors(tone),
            contentPadding = contentPadding,
            content = content,
        )
        BtnVariant.Outline -> OutlinedButton(
            onClick = onClick,
            modifier = btnModifier,
            enabled = enabled,
            shape = CircleShape,
            colors = unraidOutlinedButtonColors(),
            border = androidx.compose.foundation.BorderStroke(1.dp, UnraidTheme.colors.border),
            contentPadding = contentPadding,
            content = content,
        )
        BtnVariant.Text -> TextButton(
            onClick = onClick,
            modifier = btnModifier,
            enabled = enabled,
            shape = CircleShape,
            colors = unraidTextButtonColors(tone),
            contentPadding = contentPadding,
            content = content,
        )
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
