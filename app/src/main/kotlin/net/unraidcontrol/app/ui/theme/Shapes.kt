package net.unraidcontrol.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * Custom Material 3 [Shapes] mapping the bespoke radius vocabulary onto
 * the M3 shape slots (ADR-0030 P1).
 *
 * P2+ swaps replace bespoke components with real M3 components; those read
 * their default corner radius from `MaterialTheme.shapes`. Wiring the map
 * now means every later swap inherits the existing radii with **no**
 * appearance change at swap time — the shape decision is made once, here.
 *
 * Slot → bespoke source → first M3 consumer:
 * - extraSmall ← [UnraidDims.radField]  → `OutlinedTextField`  (P7)
 * - small      ← [UnraidDims.radChip]   → chips / `Badge`      (P6)
 * - medium     ← [DensityTokens.rad]    → `Card`               (P2)
 * - large      ← [DensityTokens.rad]    → (no consumer yet; card-consistent)
 * - extraLarge ← [UnraidDims.radDialog] → `AlertDialog`        (P7)
 *
 * Density-scaled because `rad` follows the user's Density choice; built
 * per-composition from the current [DensityTokens].
 */
fun unraidShapes(tokens: DensityTokens): Shapes = Shapes(
    extraSmall = RoundedCornerShape(UnraidDims.radField),
    small = RoundedCornerShape(UnraidDims.radChip),
    medium = RoundedCornerShape(tokens.rad),
    large = RoundedCornerShape(tokens.rad),
    extraLarge = RoundedCornerShape(UnraidDims.radDialog),
)
