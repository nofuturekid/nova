package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidTheme

/**
 * ADR-0030 P2: this is now the real Material 3 [Card], not a hand-rolled
 * `Surface`. The public signature is unchanged so every call site is
 * untouched.
 *
 * Zero-visual is load-bearing (device-gated phase):
 * - Shape is left to `CardDefaults.shape` deliberately — P1 wired
 *   `MaterialTheme.shapes.medium = RoundedCornerShape(tokens.rad)`, so
 *   the default *is* the previous explicit radius. Don't pass `shape`;
 *   that is the P1 plumbing being consumed.
 * - Elevation is forced to 0 in every state: a filled M3 `Card`
 *   otherwise draws a shadow *and* a tonal-elevation tint, neither of
 *   which the old flat `Surface` had.
 * - Colours/border route through the P1 `unraidCard*` helpers (with the
 *   per-call overrides the old API exposed).
 */
@Composable
fun UnraidCard(
    modifier: Modifier = Modifier,
    padding: Dp? = null,
    background: Color? = null,
    borderColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = UnraidTheme.tokens
    val colors = unraidCardColors(container = background)
    val border = unraidCardBorder(color = borderColor)
    val elevation = CardDefaults.cardElevation(
        defaultElevation = 0.dp,
        pressedElevation = 0.dp,
        focusedElevation = 0.dp,
        hoveredElevation = 0.dp,
        draggedElevation = 0.dp,
        disabledElevation = 0.dp,
    )
    val inner: @Composable () -> Unit = {
        Box(modifier = Modifier.padding(padding ?: tokens.pad)) { content() }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = colors,
            elevation = elevation,
            border = border,
        ) { inner() }
    } else {
        Card(
            modifier = modifier,
            colors = colors,
            elevation = elevation,
            border = border,
        ) { inner() }
    }
}
