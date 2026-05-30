package io.github.nofuturekid.nova.ui.components

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

/**
 * Single- or dual-series sparkline.
 *
 * Pass [data2]/[color2] to overlay a SECOND line on the SAME value scale as the
 * primary (the auto `hi` spans the max of both series), so e.g. a CPU and a
 * system temperature line are directly comparable. When [data2] is null the
 * output is byte-for-byte identical to the single-series path — callers that
 * never set it (CPU/Mem/Network cards) are unaffected. Only the primary [data]
 * draws the gradient fill; the secondary is stroke-only to keep the overlay
 * readable.
 */
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
    data2: List<Float>? = null,
    color2: Color? = null,
) {
    if (data.size < 2) {
        Canvas(modifier = modifier.fillMaxWidth().height(height)) {}
        return
    }
    val secondary = data2?.takeIf { it.size == data.size }
    // Single-series math is preserved exactly (byte-for-byte) when there is no
    // second series; only an overlay folds the secondary's max into the scale so
    // both lines share it and stay directly comparable.
    val autoMax = if (secondary == null) {
        data.maxOrNull() ?: 1f
    } else {
        maxOf(data.maxOrNull() ?: 1f, secondary.maxOrNull() ?: 1f)
    }
    val hi = maxValue ?: (autoMax * 1.1f).coerceAtLeast(1f)
    val lo = minValue
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height

        fun seriesPath(values: List<Float>): Path {
            val xStep = w / (values.size - 1)
            fun y(v: Float) = h - ((v - lo) / (hi - lo)) * h
            val p = Path()
            p.moveTo(0f, y(values[0]))
            if (smooth) {
                for (i in 1 until values.size) {
                    val x0 = (i - 1) * xStep
                    val x1 = i * xStep
                    val y0 = y(values[i - 1])
                    val y1 = y(values[i])
                    val cx = (x0 + x1) / 2f
                    p.cubicTo(cx, y0, cx, y1, x1, y1)
                }
            } else {
                for (i in 1 until values.size) {
                    p.lineTo(i * xStep, y(values[i]))
                }
            }
            return p
        }

        val stroke = seriesPath(data)

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

        if (secondary != null && color2 != null) {
            drawPath(
                path = seriesPath(secondary),
                color = color2,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        drawPath(
            path = stroke,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
