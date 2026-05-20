# ADR-0039: Rename app to NOVA for Lime trademark compliance

- **Status**: Accepted
- **Date**: 2026-05-20
- **Tags**: legal, brand, naming, release

## Context

ADR-0038 committed the project to renaming away from `UnraidControl` to comply with the Lime Technology Unraid® Trademark Policy §3 (Unraid + word compound prohibited) and §4 (Unraid-first construction prohibited). The new brand was to be a distinct word/phrase presentable as `[Distinct] for Unraid®`, with the maintainer making the brand-naming decision.

A brainstorming pass produced a shortlist of single-word brand marks evaluated for distinctness, EN/DE-readability, App-Store-collision risk, and backronym potential (HELM, NOVA, ORB, APEX, Beacon, Tether, Bridge, Mission, …). HELM scored highest on linguistic elegance but carries a Kubernetes-Helm collision in the DevOps space; NOVA was preferred for cross-platform optionality and a cleaner backronym expansion.

The acronym anchor initially proposed Android explicitly (`NAS Operations Viewer for Android`), which would have foreclosed iOS / web targets. Substituting the Android-specific letter with a platform-neutral one (`Anywhere`) keeps the backronym intact while leaving the cross-platform door open. iOS — if ever pursued — gets its own ADR (the GPL-v3-vs-App-Store-ToS conflict is a substantive concern that supersedes ADR-0021 in that scope).

## Decision

**Brand mark:** `NOVA` (single-word).

**Backronym (public expansion):** `NAS Operations Viewer Anywhere`.

**Canonical form in copy / store listings:** `NOVA for Unraid®`.

**Tagline:** `NOVA for Unraid® — your NAS in your pocket`.

**Android applicationId / Kotlin package namespace:** `io.github.nofuturekid.nova`. GitHub-derived namespace — free, no domain dependency, identical for a future iOS bundle identifier without dragging "unraidcontrol" through any App-Store metadata.

**Launcher icon:** new vector mark with a star / nova-explosion motif as a placeholder, replaceable later without code changes (same pattern as the current `ic_launcher_foreground.xml` vector "UC" mark).

**Migration sequence** — each step a separate PR with device acceptance:

1. **`0.1.33-beta2` — deprecation beta** under the existing `net.unraidcontrol.app` applicationId. Adds an in-app banner informing users the next release ships as a renamed app (`NOVA`) with a different applicationId; existing installs will not auto-update and the old app should be uninstalled after the new one is installed. The Settings → About section gains a "What's changing" note pointing at the upcoming NOVA release.
2. **`0.1.33-beta3` — full rename.** Kotlin source package and Android applicationId migrate to `io.github.nofuturekid.nova`. `app_name` flips to `NOVA`. The nova-motif launcher icon ships. README / HANDOFF / CHANGELOG rebrand to NOVA. Release-asset names switch from `UnraidControl-v….apk` to `NOVA-v….apk`.
3. **`0.1.33` — first compliant Stable.** Squash promotion of beta3 after device acceptance (ADR-0027 Tier 3 gate held by the maintainer).
4. **Repo rename** `nofuturekid/UnraidControl` → `nofuturekid/nova`. Executed only after Stable 0.1.33 ships green on a real device. The Pages workflow, README badge URLs, and ADR-0033 references are updated in the same PR. GitHub provides redirects for old URLs (not forever, but long enough to bridge the transition).

## Consequences

- **Positive.** Closes the §3/§4 takedown exposure that was the last remaining trademark gap after ADR-0038's §6 README fix and 0.1.33-beta1's §6 in-app fix. Establishes a clean GitHub-derived namespace that doesn't surface the old non-compliant name in store metadata. Backronym is platform-neutral, leaving iOS / web optionality open without committing to either now.
- **Negative / trade-offs.** Changing `applicationId` means **existing installs cannot auto-update** — every current user has to manually install the new app and uninstall the old one. The deprecation beta (Step 1) mitigates by warning users one cycle in advance. The repo rename will break any external bookmarks not redirected by GitHub (the Pages URL is the main risk; we update README badge / Pages targets in the rename PR so the canonical link is always live).
- **Trigger to revisit.** (a) If a Lime-permission letter ever arrives granting continued use of `UnraidControl`, this ADR is superseded (extremely unlikely after the rename ships). (b) If a stronger trademark holder claims `NOVA` against the project, the brand is re-selected — but the migration scaffold (deprecation beta → rename beta → stable → repo rename) is reusable. (c) iOS, web, or other platform targets get their own ADR; this one does not commit to any.

## Alternatives considered

- **HELM** — preferred linguistically, dropped on Kubernetes-Helm collision in the same operational/DevOps space.
- **ORB / APEX / Beacon / Mission / Bridge** — all viable distinct words; NOVA won on cross-platform backronym potential and a clearer mobile/remote tagline.
- **`A = Android` in the backronym** — dropped to keep cross-platform optionality (would have forced re-backronyming on every new target).
- **Same applicationId, different display name only** — not viable. Lime policy targets the brand mark itself; renaming only the display string leaves `unraidcontrol` in the Android package namespace, which still surfaces the prohibited compound in store metadata.
- **`net.unraidcontrol.nova` applicationId** (lineage-preserving) — rejected: keeps the non-compliant compound in every store-metadata surface, defeating the purpose of the rename.
- **Custom domain** (e.g., `nova-control.app`) — rejected for now: adds infra ownership (registration, renewal, DNS), no clear marginal benefit over the GitHub-derived namespace.

## References

- ADR-0021 — License under GPL v3 (relevant if iOS is pursued — see Trigger to revisit (c)).
- ADR-0027 — Agent autonomy & access model (each Beta in the migration sequence is per-occurrence-approved before tag-push).
- ADR-0033 — GitHub Pages preview (the Pages URL changes during Step 4 — repo rename).
- ADR-0038 — Lime Technology trademark compliance — rename required (this ADR completes that commitment).
- HANDOFF.md — App-rename note (updated alongside this ADR with the locked-in NOVA decision and the staged migration plan).
