package net.unraidcontrol.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidTheme

/**
 * Thin wrapper over Material 3 [LinearProgressIndicator] (ADR-0030 P4).
 *
 * Zero-visual target vs. the former bespoke two-Box bar:
 *  - colours come exclusively from the P1 helper [unraidProgressColors]
 *    (indicator → `accent`/override, track → `muted @ track`/override);
 *  - the bar is pinned to `height` and clipped to a full pill
 *    `RoundedCornerShape(height/2)` — M3's default 4 dp height and its
 *    own corner are overridden so the rendered geometry is unchanged;
 *  - M3's inter-segment gap and trailing stop-indicator are suppressed
 *    (`gapSize = 0.dp`, `drawStopIndicator = {}`) — the bespoke had
 *    neither, so drawing them would be a visible delta;
 *  - the 500 ms `tween` value animation is preserved explicitly (M3's
 *    built-in progress animation spec differs from the old bespoke one),
 *    keeping motion identical.
 *
 * Residual: none expected; see ADR-0030 combined acceptance checklist.
 */
@Composable
fun UnraidProgress(
    value: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    track: Color? = null,
    height: Dp = 6.dp,
) {
    val (indicator, trackColor) = unraidProgressColors(color, track)
    val clamped = value.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = clamped, animationSpec = tween(500))
    val shape = RoundedCornerShape(height / 2)
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape),
        color = indicator,
        trackColor = trackColor,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

data class StackSegment(val weight: Float, val color: Color)

@Composable
fun StackBar(
    segments: List<StackSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    val t = UnraidTheme.colors
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(t.muted.copy(alpha = UnraidAlpha.track)),
    ) {
        segments.forEach { s ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(s.weight.coerceAtLeast(0.0001f))
                    .background(s.color),
            )
        }
    }
}
