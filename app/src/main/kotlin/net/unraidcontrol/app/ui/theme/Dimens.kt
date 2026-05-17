package net.unraidcontrol.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Non-density design tokens. Density-scaled structural spacing lives in
 * [DensityTokens]; this is for values that must stay fixed regardless of
 * the user's density choice — touch targets (accessibility) and the
 * non-card corner-radius vocabulary.
 *
 * Tokenised so the same control isn't rendered at drifting sizes/radii
 * across screens (design-audit P2).
 */
object UnraidDims {
    /** Material / Android a11y minimum interactive target. Never smaller. */
    val touchMin = 48.dp

    /** Visual diameter of an icon-button's coloured circle. The 48dp
     *  touch target is layered around this without enlarging the visual. */
    val iconButtonVisual = 40.dp

    // Non-card corner radii (card radius is density-scaled in DensityTokens.rad).
    /** Chips, small banners, tab-item highlight. */
    val radChip = 12.dp
    /** Text fields / inline inputs. */
    val radField = 14.dp
    /** Dialogs / modal surfaces. */
    val radDialog = 22.dp
}
