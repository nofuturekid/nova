package io.github.nofuturekid.nova.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

/**
 * Tap-affordance indicator for in-place-expand rows.
 *
 * Renders the [UC.ChevD] chevron-down glyph and rotates it 180° (so it
 * reads as chevron-up) when [expanded] is true. The 180 ms tween matches
 * M3 short-motion conventions and the local [UnraidProgress] precedent
 * of an explicit `tween` animation spec.
 *
 * Purely visual — callers keep their own `.clickable { expanded = !expanded }`.
 */
@Composable
fun ExpandIndicator(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = UnraidTheme.colors.muted,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "ExpandIndicator.rotation",
    )
    androidx.compose.foundation.layout.Box(modifier = modifier.rotate(rotation)) {
        UC.ChevD(size, tint)
    }
}
