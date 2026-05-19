# ADR-0030: UI modernization & tech-debt roadmap (phased)

- **Status**: Accepted — P1 device-accepted (2026-05-17, not promoted to stable); P2 device-accepted (2026-05-17, not promoted to stable); P3 device-accepted (2026-05-17, not promoted to stable); **P4–P7 + D1 + D2 combined-device-accepted (2026-05-17, not promoted to stable)** — implemented 2026-05-17 as ONE combined beta (v0.1.30-beta7), per-phase gate waived (see Amendment 2026-05-17)
- **Date**: 2026-05-17
- **Tags**: ui, process, data, build

## Context

The UI is **deliberately bespoke** — `UnraidButton`, `UnraidIconButton`,
`UnraidCard`, `UnraidField`, `ConfirmDialog`, `Pill`, `UnraidProgress`
are hand-built on Compose primitives and themed through a custom
`UnraidColors`/`DensityTokens`/`Tone` CompositionLocal system, not the
Jetpack Material 3 components. A design audit (shipped as the
accessibility/consistency pass in PR #103 / v0.1.30-beta2) closed the
*guideline* gaps (contentDescription, 48 dp targets, ripple, NavigationBar)
**without** changing the visual identity, and explicitly deferred the
deeper question: should the bespoke layer be migrated to real M3
components, and is the accumulated structural debt worth paying down?

Several debts surfaced over recent work and are individually small but
share a root (the bespoke component/token layer):

- Bespoke-vs-M3 across the whole interactive surface (the audit's
  systemic finding).
- `UnraidButton`/`UnraidIconButton` near-duplication; scattered,
  un-tokenised colour alphas (`0.08…0.20`) for semantically identical
  surfaces (audit P2, only partially tokenised so far).
- `app/build.gradle.kts` `srcDir(...)` AGP-9 deprecation warning
  (surfaced during the PR #101 local-CI acceptance run; ties to the
  ADR-0023 AGP-10 cleanup).
- The ADR-0012 install-pipeline duplication across `MainViewModel` /
  `SettingsViewModel` (the `ownsInstall` guard added more parallel
  logic; ADR-0012's own revisit trigger is the singleton extraction).

The hard constraint shaping all of this: **this UI is not
visually/functionally verifiable by CI or the local-CI container**
(no emulator; instrumented/visual testing is explicitly out of scope —
see `docs/local-build.md`). Any change that alters appearance can only
be accepted by the maintainer on-device (ADR-0027 Tier 3).

(Note: the pervasive per-call `ApolloClient` leak is **not** in this
roadmap — ADR-0028's factory cache already closed it comprehensively at
the chokepoint.)

## Decision

Adopt **one phased roadmap** (this ADR) for UI modernization *and* the
shared-root tech debt, rather than a separate "cleanup refactor" or a
big-bang rewrite. Principles:

- **No big-bang.** Each phase is independently shippable, ordered
  low-risk/high-value → high-divergence.
- **Per-phase gate.** Every phase that changes appearance is its own
  beta + on-device acceptance by the maintainer (ADR-0027 Tier 3); the
  agent may drive build/CI/merge/beta-bump/tag autonomously but **not**
  the visual sign-off or any stable promotion.
- **Foundation first.** A zero-visual-change theming phase de-risks
  every later component swap.
- This ADR **supersedes nothing**; it is a living index. Each phase
  links back here; phases may spawn their own ADR if a phase makes a
  non-obvious trade-off.

### Phases — UI / M3 (visual risk per the scope survey)

| # | Phase | Visual risk | Rationale |
|---|---|---|---|
| P1 | Theme plumbing: custom M3 `Shapes` (map `rad`/`radField`/`radDialog`) + per-component `*Defaults.colors()` derived from `UnraidColors`; **harmonise** the scattered alphas to one value per semantic role | **Low** | Unblocks every later swap. Originally scoped "None / no gate" — see P1 implementation note: harmonising drift moves pixels, so P1 is now device-gated — device-accepted 2026-05-17 (provisional; stable promotion still maintainer-only) |
| P2 | `UnraidCard` → M3 `Card` (flat/border via `CardDefaults`) | Low–Med | **Implemented 2026-05-17** — see P2 implementation note. Consumes the P1 foundation (`unraidCard*` helpers + `Shapes.medium`); targets **zero-visual** (device-gated) — device-accepted 2026-05-17 (provisional; stable promotion still maintainer-only). Highest reuse (8 call sites), low semantics |
| P3 | `UnraidIconButton` → tonal `IconButton`; fold `UnraidButton`/`UnraidIconButton` duplication | Med | **Implemented 2026-05-17** — see P3 implementation note. Consumes the P1 foundation (`unraid*IconButtonColors` helpers); targets **zero-visual** (device-gated) — device-accepted 2026-05-17 (provisional; stable promotion still maintainer-only). Folds the duplicated tone→colour logic into the P1 `ComponentColors` helpers (single source of truth); the `Button`-family structural swap remains P5. Biggest blast radius (11 screens); tint/circle maps cleanly |
| P4 | `UnraidProgress` → `LinearProgressIndicator` (keep `StackBar` bespoke — no M3 equivalent) | Med | **Implemented 2026-05-17** — combined beta per Amendment 2026-05-17. Thin wrapper over M3 `LinearProgressIndicator`; colours via the new P1 `unraidProgressColors` helper; height/corner pinned, M3 gap + stop-indicator suppressed, 500 ms tween preserved → zero-visual — device-accepted 2026-05-17 (combined beta v0.1.30-beta7, provisional; stable promotion still maintainer-only) |
| P5 | `UnraidButton` → `Button` family | High | **Implemented 2026-05-17** — combined beta per Amendment 2026-05-17. Filled/Tonal/Outline/Text → `Button`/`FilledTonalButton`/`OutlinedButton`/`TextButton`; colours EXCLUSIVELY via P1 helpers; pill/touch/padding preserved. Includes the deferred P3 `Tone.Neutral` decision: Neutral now renders muted (was wrongly accent) — INTENDED change at 4 Cancel/Later/Close call sites — device-accepted 2026-05-17 (combined beta v0.1.30-beta7, provisional; stable promotion still maintainer-only) |
| P6 | `Pill` → `Badge`/chip (or retain) | Med | **Implemented 2026-05-17** — combined beta per Amendment 2026-05-17. M3 `Badge`'s fixed min-size + 4 dp padding would shift the dense status rows (10 dp/4 dp padded pill + 6 dp dot), so per the ADR's "or retain" the bespoke rendering is RETAINED at all 15 call sites — zero-visual, no layout shift — device-accepted 2026-05-17 (combined beta v0.1.30-beta7, provisional; stable promotion still maintainer-only) |
| P7 | `ConfirmDialog` → `AlertDialog`; `UnraidField` → `OutlinedTextField` | High | **Implemented 2026-05-17** — combined beta per Amendment 2026-05-17. `ConfirmDialog` → M3 `AlertDialog`; `UnraidField` (4 call sites — ADR's "single" was wrong) → M3 `OutlinedTextField`, colours via P1 `unraidTextFieldColors`. M3-idiomatic appearance accepted over zero-visual (Rule 13) — device-accepted 2026-05-17 (combined beta v0.1.30-beta7, provisional; stable promotion still maintainer-only) |

### Phases — cross-cutting non-UI debt (independent, no device gate)

| # | Phase | Note |
|---|---|---|
| D1 | Resolve `app/build.gradle.kts` `srcDir(...)` AGP-9 deprecation (`directories` set) | Pairs with the ADR-0023 AGP-10 cleanup |
| D2 | ADR-0012 install-pipeline: extract `@Singleton UpdateController`, drop the `MainViewModel`/`SettingsViewModel` duplication + `ownsInstall` guards | **Implemented 2026-05-17** — combined beta per Amendment 2026-05-17. `UpdateController` owns the one `installState`/collector/`installUpdate`/`resetInstall`; both ViewModels forward it, public API unchanged; `ApkInstaller` interface + `UpdateModule` `@Binds` added for unit-testability; unit tests added. **Intended behaviour change:** install progress now reflected on both screens regardless of origin (per-screen `ownsInstall` isolation removed) — see ADR-0012 revisit + checklist below. Actions ADR-0012's documented revisit trigger |

### P1 implementation note (2026-05-17)

P1 was scoped at start. Three decisions changed it from the table's
original intent:

- **Harmonise, not name-only.** The scattered alphas genuinely *drift*
  for semantically identical surfaces (tonal fills 0.12/0.13/0.14/0.16,
  soft callouts 0.08/0.10, hairlines 0.16/0.20, track 0.18/0.20). The
  maintainer chose to collapse each role to **one** value (real debt
  paydown) rather than merely name the existing drift. That moves
  pixels → P1 **is now an ADR-0027 Tier 3 device-gated visual phase**,
  not the original "no gate". This supersedes the P1 row's original
  "**None**" risk.
- **Whole-UI scope.** Tokens applied across components *and* all screens
  (~45 sites), not just the bespoke component layer. Gradient ramp stops
  (Sparkline, ContainerIcon) and text-colour opacities (input
  placeholder, Docker info caption) are deliberately **excluded** — not
  surfaces; collapsing them would warp type/gradient legibility.
- **Foundation built now.** Custom M3 `Shapes` (wired into
  `MaterialTheme`) and the per-component `*Defaults.colors()` helpers
  ship in P1 as named, unconsumed foundation; P2+ swaps consume and
  visually verify them.

Harmonisation targets prefer the **published Material 3 opacity token**
where a role maps (disabled container = onSurface @ 12 %, soft/hover
state-layer = 8 %), per the design north-star: *the app should look and
feel as if Google itself had written it*. Net rendered shifts for the
maintainer's on-device acceptance: neutral/secondary tonal fills
0.12–0.14 → **0.16**; disabled control fill 0.14 → **0.12**; some soft
callouts 0.10 → **0.08**; Overview parity hairline 0.16 → **0.20**;
StackBar free segment 0.20 → **0.18**; disk-type chip 0.13 → **0.16**.
All other in-scope sites keep their pre-P1 value (only the *name*
changed).

New foundation: `theme/Alpha.kt` (`UnraidAlpha`), `theme/Shapes.kt`
(`unraidShapes`), `components/ComponentColors.kt` (`unraid*Colors()`).

### P2 implementation note (2026-05-17)

`UnraidCard` is now the real Material 3 `Card`, not the hand-rolled
`Surface`. The public signature is **unchanged**, so all 8 call sites are
untouched (pure foundation-consuming swap, no screen edits).

Zero-visual is load-bearing here — it is the device-gated acceptance
criterion — and rests on three points:

- **Shape:** left to `CardDefaults.shape` deliberately, *not* passed
  explicitly. P1 wired `MaterialTheme.shapes.medium =
  RoundedCornerShape(tokens.rad)`, and `CardDefaults.shape` resolves to
  `Shapes.medium`, so the default *is* the previous explicit radius. This
  is precisely the P1 plumbing being consumed.
- **Elevation:** forced to 0 in **every** state
  (default/pressed/focused/hovered/dragged/disabled). A filled M3 `Card`
  otherwise draws both a drop shadow *and* a tonal-elevation tint,
  neither of which the old flat `Surface` had — pinning elevation to 0 is
  what keeps the swap visually inert.
- **Colours/border:** routed through the P1 `unraidCard*` helpers
  (`unraidCardColors(container=)` / `unraidCardBorder(color=)`), which
  preserve the per-call `background`/`borderColor` overrides the old API
  exposed (extended with optional override params for exactly this).

The only thing to verify on-device is the **absence of any new
shadow/tint on cards** vs. the previous flat surface; everything else is
held constant by the P1 foundation.

Device-accepted 2026-05-17 (v0.1.30-beta5, PR #112); provisional, not
promoted to stable.

### P3 implementation note (2026-05-17)

`UnraidIconButton` is now the real Material 3 `IconButton` /
`FilledTonalIconButton`, not the hand-rolled `Box(...).background()`. The
public signature is **unchanged** (`icon, onClick, contentDescription,
modifier, size, tone, enabled`), so all 21 `UnraidIconButton(` call sites
are untouched (pure foundation-consuming swap, no screen edits).

The ADR's named "near-duplication" was the **duplicated tone→colour
logic**: a file-local `onToneColor` in `Buttons.kt` plus inline
`when (tone) { … }` derivations in both `UnraidButton` and
`UnraidIconButton`. P3 collapses this to a single source of truth — the
icon-button colours come exclusively from the P1
`unraidTonalIconButtonColors` / `unraidPlainIconButtonColors` helpers, and
`UnraidButton`'s on-accent legibility rule now consumes the shared
`onTone` in `ComponentColors.kt` (its private copy deleted). The
`Button`-family **structural** swap (pill `Row` → `Button`) stays out of
scope — that remains P5; only the colour derivation moved.

Zero-visual is load-bearing here — it is the device-gated acceptance
criterion — and rests on:

- **Signature unchanged.** No call site changes; the 21 icon-button and
  33 button sites compile untouched.
- **Colours via P1 helpers.** `unraidTonalIconButtonColors(tone)` is
  `tone.base().copy(alpha = tonalFill)` for every tone; the bespoke
  mapping was identical post-P1 (accent already used `accentDim`, which
  *is* `accent.copy(alpha = tonalFill)`). `tone = null` →
  `unraidPlainIconButtonColors()` = transparent container. No pixel shift.
- **Container pinned to `size`, not M3 40 dp.** The M3 `IconButton`
  defaults to a 40 dp container; it is forced to `Modifier.size(size)`
  and clipped to `CircleShape`, so the rendered coloured circle is
  exactly the previous `size` param.
- **Touch target preserved.** The ≥48 dp target is still an outer `Box`
  with `sizeIn(minWidth/minHeight = touchMin)` wrapping the IconButton
  (audit P0 unchanged).
- **a11y preserved.** `contentDescription` stays compiler-required and is
  wired to the control's `semantics { contentDescription; onClick(label) }`
  — the same announcement the previous `clickable(onClickLabel = …)`
  produced; TalkBack still announces it.
- **Disabled.** M3's `IconButton`/`FilledTonalIconButton` defaults dim
  disabled content to the M3 0.38 alpha — equivalent to the previous
  explicit `.alpha(0.38f)` on the icon.

The **one** on-device verification point: ripple extent. The bespoke code
used an *unbounded* ripple with `radius = touchMin / 2`; the M3
IconButton uses its default ripple **bounded to the container circle**.
Confirm there is no perceptible change in ripple extent on icon buttons
on-device.

Device-accepted 2026-05-17 (v0.1.30-beta6, PR #115); provisional, not
promoted to stable.

## Amendment 2026-05-17 — combined release of remainder (maintainer override)

The maintainer **consciously overrode** this ADR's "No big-bang" /
per-phase device-acceptance core principle for the **remaining** phases.
The decision, recorded here for traceability:

- **Scope of the override.** P4, P5, P6, P7 **and** D1 (plus D2, done
  separately but riding the **same** beta) are implemented together and
  shipped as **ONE combined device-gated beta**, not five independently
  gated betas.
- **Per-phase granularity waived.** The "each phase independently
  shippable / independently device-accepted" guarantee is explicitly
  waived for this remainder. There is **one** combined on-device
  acceptance covering P4–P7 + D1 together (see checklist below), not a
  gate per phase.
- **Tier-3 human-only acceptance is NOT waived.** The on-device
  acceptance itself still happens and remains a human-only gate
  (ADR-0027 Tier 3). What changed is its *granularity* (one combined
  pass), not its existence.
- **Stable promotion still maintainer-only.** This combined beta is
  provisional. Promotion to stable remains exclusively the maintainer's
  decision and is unaffected by this amendment.
- **Regression-attribution risk explicitly accepted.** Combining the
  remaining phases into one beta means a regression surfaced on-device
  cannot be attributed to a single phase from the gate alone; bisection
  may be required. The maintainer accepts this trade-off in exchange for
  collapsing five gates into one.

### Combined acceptance checklist (P4–P7 + D1 + D2, 2026-05-17)

On-device verification points the maintainer must check before
accepting this combined beta:

- **P5 — Cancel / Later / Close buttons change from accent-coloured to
  neutral/muted (INTENDED, semantically correct).** This is the
  deferred P3 `Tone.Neutral` decision: the old `else → accent` lumped
  `Tone.Accent` and `Tone.Neutral` together (a latent bug). The P1
  helpers map `Tone.Neutral → muted`. Affected call sites (4): the
  Cancel button in `AddEditServerSheet.kt` and `ConfirmDialog.kt`, and
  the Later/Close buttons in `UpdateDialog.kt` (×2). Confirm the muted
  rendering reads correctly. All other tone/variant combinations are
  bit-identical (zero-visual) via the P1 helpers.
- **P7 — `ConfirmDialog` adopts the M3 `AlertDialog` idiom (INTENDED,
  Rule 13).** Expect: standard M3 confirm/dismiss button row, M3 dialog
  elevation + scrim, M3 title/text typography slots. Preserved: theme
  surface colour, `radDialog` corner, the tone icon-chip. Confirm
  layout/elevation read as intended.
- **P7 — `UnraidField` adopts the M3 `OutlinedTextField` idiom
  (INTENDED, Rule 13).** Expect: floating/animating label, M3 outline
  focus motion, M3 supporting-text/error slot. Colours route through the
  P1 `unraidTextFieldColors` helper (same softFill container +
  accent/border/danger roles). 4 call sites (all in
  `AddEditServerSheet.kt`) — the ADR table's "single call site" was
  wrong. Confirm label motion + outline focus read as intended.
- **P4 — `UnraidProgress` → M3 `LinearProgressIndicator`: expected
  zero-visual.** Height/corner pinned to the bespoke geometry, M3
  inter-segment gap + trailing stop-indicator suppressed, the 500 ms
  `tween` value animation preserved. No residual pixel diff found in
  review; confirm no visible change at the 5 call sites (array sync /
  rebuild bars, overview, update install).
- **P6 — `Pill`: bespoke RETAINED, expected zero-visual.** M3 `Badge`'s
  fixed min-size + 4 dp internal padding would shrink the 10 dp/4 dp
  padded pill and shift the dense status rows, so per the ADR's "or
  retain" all 15 call sites keep the bespoke rendering unchanged.
  Confirm no layout shift in Docker / VM / array / overview status rows.
- **D1 — AGP-9 srcDir deprecation: no behaviour change.** The deprecated
  `android.sourceSets["main"].kotlin.srcDir(...)` accessor was removed;
  under AGP 9 built-in Kotlin `src/main/kotlin` is already a default
  source directory, so the registration was redundant. Verified green
  via `./scripts/local-ci.sh`; nothing to verify on-device.
- **D2: install progress now shown on both Overview and Settings
  regardless of origin screen (intended per ADR-0012).** ADR-0012's
  duplicate install pipeline was extracted to a `@Singleton
  UpdateController`; both ViewModels forward its single shared
  `installState`. The old per-screen `ownsInstall` isolation is
  deliberately gone (one install, one source of truth — see ADR-0012
  "Revisit (actioned, ADR-0030 D2)"). Public composable API unchanged.
  Confirm an install started from either screen shows the same progress
  on both. Unit-tested (`UpdateControllerTest`); also
  green via `./scripts/local-ci.sh`.

Combined beta v0.1.30-beta7 device-accepted 2026-05-17 (PR #117);
provisional, not promoted to stable. The maintainer accepted the single
combined on-device pass covering P4–P7 + D1 + D2; the checklist above is
retained as the record of what was verified.

## Consequences

**Positive**
- One coherent, reviewable roadmap instead of scattered ad-hoc refactors;
  debt is named and ordered, not silently growing.
- Foundation-first means most component swaps become low-risk colour/shape
  plumbing, not rewrites.
- Each phase is small enough to revert cleanly.

**Negative / trade-offs**
- Long-lived multi-phase initiative; partial state (some M3, some
  bespoke) persists between phases — acceptable, each phase is
  self-consistent.
- The visual phases bottleneck on maintainer device-acceptance; the
  agent cannot fully autonomously complete them (by design, ADR-0027).
- `Tone`/`Tone.colors()` must survive as the shared colour source
  through the whole migration; it is a cross-phase dependency, not
  itself replaced.

**Trigger to revisit**
- P1 deliberately *does* change appearance (alpha harmonisation). If the
  on-device acceptance rejects the harmonised look, fall back to the
  name-only/zero-visual variant (token each value as-is, keep the drift)
  and reconsider whether full M3 changes are worth it vs. retaining
  bespoke with only the audit's a11y retrofit (already shipped).
- If M3/Compose ships a theming path that maps custom palettes without
  per-component `Defaults` plumbing, P1 simplifies.
- If the app stays single-maintainer indefinitely, D2 (ADR-0012
  singleton) may never reach its third-caller trigger — keep it parked,
  don't force it.

## Alternatives considered

- **Big-bang rewrite to M3.** Rejected: unverifiable without device/
  emulator; a single PR changing the whole look is unreviewable and
  unrevertable in practice.
- **Status quo (a11y retrofit only, keep bespoke forever).** Defensible
  — #103 already meets Google guidelines. Rejected as the *default*
  because the bespoke layer is the shared root of recurring drift
  (token/dedup/duplication); paying it down compounds.
- **Separate "cleanup refactor" PR-stack parallel to M3 migration.**
  Rejected: same files, same root; two roadmaps would conflict and
  double the review surface. One sequenced roadmap is cheaper.

## References

- Supersedes nothing. Living index; phases reference back here.
- ADR-0027 (tiered autonomy — the per-phase device-acceptance gate).
- ADR-0012 (install duplication — phase D2 actions its revisit trigger).
- ADR-0023 (AGP-9 toolchain — phase D1 pairs with its AGP-10 cleanup).
- ADR-0028 (Apollo client cache — the one debt explicitly *out* of this
  roadmap, already closed).
- ADR-0029 (CI build-skip inversion — why this docs-only ADR PR skips
  the APK build).
- PR #103 / v0.1.30-beta2 — the shipped a11y/consistency pass this
  roadmap continues from; the bespoke→M3 scope survey informing the
  phase order.

## Post-P7 follow-ups (2026-05-18 code-review triage)

UI/M3 debt surfaced by the 2026-05-18 review; in ADR-0030 scope, not ad-hoc — own phase/beta when scheduled.

- **F1 — `SettingsScreen` scroll:** plain `Column` (no `verticalScroll`), clips on short screens (≈360×640dp). Add `verticalScroll`/lazy. (triage #13)
- **F2 — on-accent literal `Color(0xFF06120E)`** at ArrayTab/ContainerDetailSheet(×2)/VmDetailSheet/VmsTab(×2): bypasses `onPrimary`/`onTone()` → invisible on light accents (Rule 13). Route through P1 helpers. (triage #15)
- **F3 — bespoke `Toggle`** in `SettingsScreen` (hardcoded white thumb, no `Role.Switch`/`toggleable`) → M3 `Switch`. (triage #16)
