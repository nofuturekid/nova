package net.unraidcontrol.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Density { Compact, Balanced, Spacious }

@Immutable
data class DensityTokens(
    val pad: Dp,
    val gap: Dp,
    val rad: Dp,
)

fun tokensFor(density: Density): DensityTokens = when (density) {
    Density.Compact  -> DensityTokens(pad = 12.dp, gap =  8.dp, rad = 14.dp)
    Density.Balanced -> DensityTokens(pad = 16.dp, gap = 12.dp, rad = 16.dp)
    Density.Spacious -> DensityTokens(pad = 20.dp, gap = 16.dp, rad = 18.dp)
}
