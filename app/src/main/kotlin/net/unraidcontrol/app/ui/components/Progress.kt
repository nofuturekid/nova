package net.unraidcontrol.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun UnraidProgress(
    value: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    track: Color? = null,
    height: Dp = 6.dp,
) {
    val t = UnraidTheme.colors
    val clamped = value.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = clamped, animationSpec = tween(500))
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(track ?: Color.White.copy(alpha = 0.06f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(shape)
                .background(color ?: t.accent),
        )
    }
}

data class StackSegment(val weight: Float, val color: Color)

@Composable
fun StackBar(
    segments: List<StackSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f)),
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
