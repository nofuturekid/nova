package net.unraidcontrol.app.ui.theme

/**
 * Semantic alpha tokens (ADR-0030 P1).
 *
 * Before P1 the same *semantic surface* was drawn at drifting opacities
 * across the UI (tonal fills at 0.12/0.13/0.14/0.16, soft callouts at
 * 0.08/0.10, hairlines at 0.16/0.20, …). P1 collapses each role to **one**
 * value so a control can no longer render at different opacities on
 * different screens.
 *
 * Harmonisation is a *deliberate* visual change (ADR-0030 P1 is therefore
 * device-gated, ADR-0027 Tier 3). Where a role maps to a published
 * Material 3 opacity token the M3 value is chosen, so the result already
 * reads as Google's own system (the design north-star) rather than an
 * arbitrary number.
 *
 * Scope: `Color.copy(alpha=…)` used for a *surface* — fill, hairline
 * border, track or scrim. Gradient ramp stops (Sparkline, ContainerIcon)
 * and text-colour opacities (input placeholder, info caption) are
 * intentionally **not** tokenised here — they are not surfaces and
 * collapsing them would warp type/gradient legibility.
 */
object UnraidAlpha {
    /**
     * Soft filled background behind a coloured/neutral control, chip,
     * pill or icon-button (the "tonal container").
     * Harmonised from 0.12 / 0.13 / 0.14 / 0.16. Anchored to the existing
     * `accentDim` constant (0.16) — the de-facto canonical tonal opacity
     * already used by every `Tone.Accent` surface.
     */
    const val tonalFill = 0.16f

    /**
     * Disabled control container fill. Material 3 publishes this exactly:
     * disabled container = onSurface @ 12 %. Was 0.14 → 0.12 (toward M3).
     */
    const val disabledFill = 0.12f

    /**
     * Subtle tinted callout / banner / input rest background.
     * Harmonised from 0.08 / 0.10. Matches the M3 hover state-layer (8 %).
     */
    const val softFill = 0.08f

    /** 1 dp hairline around a soft callout. Harmonised from 0.16 / 0.20. */
    const val softBorder = 0.20f

    /** Unfilled progress / stack-bar track rail. Harmonised from 0.18 / 0.20. */
    const val track = 0.18f

    /** Bottom-sheet drag grabber / strong muted handle. Already uniform. */
    const val grabber = 0.40f

    /** Update-banner accent wash. Single-role. */
    const val emphasisFill = 0.20f

    /** Update-banner stronger accent border. Single-role. */
    const val emphasisBorder = 0.45f

    /** Off-state track of the Settings toggle. Single-role. */
    const val controlTrackOff = 0.20f

    /** Connection-test result tint border (Ok / Fail). Single-role. */
    const val testStateBorder = 0.30f

    /** Active / selected server-row wash. Single-role. */
    const val selectedRowFill = 0.04f
}
