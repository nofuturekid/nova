# ADR-0038: Lime Technology trademark compliance — rename required (pending name)

- **Status**: Accepted
- **Date**: 2026-05-20
- **Tags**: legal, brand, docs

## Context

Lime Technology publishes the **Unraid® Trademark Policy** (effective 2025-10-01, last revised 2025-09-02; <https://unraid.net/policies>). Two clauses bear directly on this project's identity:

- **§3 — Prohibited app-naming patterns.** "Unraid" combined with other words into a single term (the policy's own examples: *Unraider*, *Unraidify*, *UnraidPro*) is not permitted.
- **§4 — Permitted form.** The only acceptable construction is `[YourApp] for Unraid®`. "'Unraid [YourApp]' or any construction that places 'Unraid' first is not permitted."

This project ships as **`UnraidControl`** (package `net.unraidcontrol.app`, GitHub repo `nofuturekid/UnraidControl`). That is the exact compound pattern §3 prohibits — same shape as the policy's verbatim disallowed examples. §6 additionally requires explicit attribution of "Unraid®" as a registered trademark in any project that uses the term factually.

A Tier-1 read-only asset audit on 2026-05-20 confirmed the violation is **name-only**:

| Surface | Status |
|---|---|
| App launcher icon (`drawable/ic_launcher_*.xml`) | Original "UC" vector mark — no Unraid swoosh / logo |
| Mipmap pool / drawable pool | Same two vectors only; no bitmap brand assets |
| README screenshots (`docs/screenshots/*.png`) | All show our own UI; no Lime brand asset surfaces |
| Compound app / package / repo name | **Violates §3** |
| In-UI text references ("Unraid 7.3.0", "Unraid API") | Factual references — permitted under §1/§6 once registered-mark attribution is in place |
| README attribution block | Mentions the trademark but lacks the §6 registered-mark form |

§5 (logo use) is satisfied; §3/§4 (naming) and §6 (attribution form) are not.

## Decision

1. **Commit to renaming the app** to a §3/§4-compliant identity. The new identity must be a distinct word or phrase that does **not** combine "Unraid" with other words; project copy and store listings will use the `[Distinct] for Unraid®` form where the relationship is described.
2. **Capture this commitment in ADR-0038 now**, before the new name is chosen, so the policy review is durable and the §7 takedown-exposure surface stays minimal.
3. **Update README attribution to the §6 form immediately** ("Unraid® is a registered trademark of Lime Technology, Inc."), independent of the rename. This change ships with this ADR in a single docs-only PR.
4. **Defer the rename execution** (`applicationId`, package, repo, deprecation cycle, first compliant Stable) to a dedicated `0.1.33`-cycle ADR drafted once the new name is decided. That ADR will cover the package migration, the transition beta carrying a deprecation banner that points users at the new app, and the policy on the abandoned `net.unraidcontrol.app` namespace.

## Consequences

- **Positive.** Eliminates §3/§4 takedown exposure as soon as the rename ships; the §6 attribution fix lands immediately via this docs-only PR (zero shipping-app changes). Establishes a single durable record of the policy mapping so the future rename ADR can focus on migration mechanics rather than re-litigating the legal scope.
- **Negative / trade-offs.** Renaming a distributed app is disruptive: existing installs sit under the old `applicationId` and will not auto-migrate to the new package; at least one transition beta must carry a deprecation banner pointing users at the new app. Repo rename costs all existing release-URL bookmarks (GitHub's redirect helps but is not guaranteed permanent for every surface).
- **Trigger to revisit.** (a) If Lime Technology grants explicit written permission to continue under the current name, this ADR is superseded with that permission attached. (b) If the policy changes materially before the rename ships, the §3/§4 analysis is re-done against the new text. (c) If the new brand-name decision is taken, the rename-cycle ADR is drafted and this ADR is linked from it.

## Alternatives considered

- **Continue under `UnraidControl`.** Rejected. §7 of the policy allows immediate enforcement (DMCA / store takedown); the cost of a compelled rename under timeline pressure is strictly worse than a planned one.
- **Seek written permission from Lime Technology.** Considered. The policy invites such requests but offers no SLA. Rather than block compliance on an open-ended legal correspondence, the project commits to renaming; if a permission letter later arrives, ADR-0038 is superseded.
- **Defer everything to the rename PR.** Rejected. The §6 attribution gap and the durable policy mapping cost nothing to fix now and reduce §7 exposure immediately, even before the new name is chosen.

## References

- Lime Technology — Unraid® Trademark Policy (effective 2025-10-01): <https://unraid.net/policies>
- ADR-0021 — Relicense to GPL v3 (the project's existing legal-scope ADR).
- ADR-0027 — Agent autonomy & access model (Tier-1 act-first authorized the asset audit on which this ADR rests).
- HANDOFF.md — App-rename note (updated alongside this ADR).
