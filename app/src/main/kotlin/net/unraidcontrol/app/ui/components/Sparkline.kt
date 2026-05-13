package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Sparkline(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    minValue: Float = 0f,
    maxValue: Float? = null,
    fill: Boolean = true,
    smooth: Boolean = true,
) {
    if (data.size < 2) {
        Canvas(modifier = modifier.fillMaxWidth().height(height)) {}
        return
    }
    val hi = maxValue ?: ((data.maxOrNull() ?: 1f) * 1.1f).coerceAtLeast(1f)
    val lo = minValue
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val xStep = w / (data.size - 1)
        fun y(v: Float) = h - ((v - lo) / (hi - lo)) * h

        val stroke = Path()
        stroke.moveTo(0f, y(data[0]))
        if (smooth) {
            for (i in 1 until data.size) {
                val x0 = (i - 1) * xStep
                val x1 = i * xStep
                val y0 = y(data[i - 1])
                val y1 = y(data[i])
                val cx = (x0 + x1) / 2f
                stroke.cubicTo(cx, y0, cx, y1, x1, y1)
            }
        } else {
            for (i in 1 until data.size) {
                stroke.lineTo(i * xStep, y(data[i]))
            }
        }

        if (fill) {
            val filled = Path().apply {
                addPath(stroke)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(
                path = filled,
                brush = Brush.verticalGradient(
                    0f to color.copy(alpha = 0.35f),
                    1f to color.copy(alpha = 0f),
                ),
            )
        }

        drawPath(
            path = stroke,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
