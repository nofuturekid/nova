package net.unraidcontrol.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Density { Compact, Balanced, Spacious }

/**
 * Spacing tokens that scale with the user's Density choice.
 *
 * Only *structural* spacing is tokenised — card interiors, the gap
 * between cards in a scrolling list, the screen's horizontal content
 * inset, and corner radius. Micro-spacing inside a card (icon↔label
 * gaps, 2–8 dp) stays hardcoded on purpose: scaling those would warp
 * component internals rather than make the layout breathe.
 *
 * `Balanced` deliberately matches the values the UI was hardcoded to
 * before density was wired up, so the default appearance is unchanged
 * — only Compact / Spacious now visibly differ.
 */
@Immutable
data class DensityTokens(
    /** Dense rows / tiles (Docker rows, stat tiles). Was ~12 dp. */
    val padTight: Dp,
    /** Standard card interior. Was ~14–16 dp. */
    val pad: Dp,
    /** Large feature cards (Array status, Overview hero). Was ~18 dp. */
    val padHero: Dp,
    /** Vertical gap between cards in a scrolling list. Was ~8–12 dp. */
    val gap: Dp,
    /** Horizontal screen content inset for top-level lists. Was 16 dp. */
    val screenPad: Dp,
    /** Card corner radius. */
    val rad: Dp,
)

fun tokensFor(density: Density): DensityTokens = when (density) {
    Density.Compact  -> DensityTokens(padTight = 10.dp, pad = 13.dp, padHero = 15.dp, gap =  8.dp, screenPad = 12.dp, rad = 14.dp)
    Density.Balanced -> DensityTokens(padTight = 12.dp, pad = 16.dp, padHero = 18.dp, gap = 11.dp, screenPad = 16.dp, rad = 16.dp)
    Density.Spacious -> DensityTokens(padTight = 16.dp, pad = 20.dp, padHero = 24.dp, gap = 15.dp, screenPad = 22.dp, rad = 18.dp)
}
