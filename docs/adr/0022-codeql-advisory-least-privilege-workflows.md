# ADR-0022: CodeQL is advisory; workflows run least-privilege

- **Status**: Accepted
- **Date**: 2026-05-15
- **Tags**: ci, security

## Context

GitHub CodeQL code scanning was enabled on the repository via **Default
Setup** (configured in repo Settings → Code security, no
`.github/workflows/codeql.yml` — it runs as the managed
`github-code-scanning/codeql` workflow on push to `main`, on PRs, and on
a weekly schedule, language auto-detected as `java-kotlin`).

Its first analyses produced **zero application-code findings** and **five
`actions/missing-workflow-permissions` warnings** — `ci.yml` (×4) and
`cache-warm.yml` (×1) declared no explicit `permissions:` block and so
inherited the default, potentially over-broad, `GITHUB_TOKEN` scope.
`release.yml` already declared `permissions: contents: write` and was not
flagged.

Two questions follow from enabling CodeQL:

1. Should a green CodeQL run be a **required** status check for merging?
2. How do we keep `GITHUB_TOKEN` least-privilege so the finding class
   doesn't recur for future workflows?

Constraints in play:
- ADR-0009 gives docs-only PRs a ~10-second fast path. CodeQL Default
  Setup runs on *every* PR regardless of content and takes minutes —
  making it a required check would erode that fast path for docs PRs.
- This is effectively a solo project; a required scanner that blocks
  merges on every first-seen (often low-severity or false-positive)
  finding adds friction disproportionate to the risk at this stage.
- The required status check today is the single `build` aggregator
  (ADR-0019). Branch-protection config is intentionally minimal.

## Decision

**1. CodeQL stays advisory — not a required status check.** It runs,
posts results to Security → Code scanning, and is reviewed there.
Merges are gated only by the existing `build` aggregator. The ADR-0009
docs fast path and the low-friction solo flow are preserved. Revisit
making it required once the alert baseline is clean and stable (see
trigger below).

**2. Every workflow declares an explicit least-privilege
`permissions:` block.** Convention for this repo:

| Workflow | `permissions:` | Why |
|---|---|---|
| `ci.yml` | `contents: read` | checkout + build + same-run artifact upload |
| `cache-warm.yml` | `contents: read` | checkout + Gradle dependency resolve |
| `release.yml` | `contents: write` | creates a GitHub Release (already had this) |

New workflows MUST add a top-level `permissions:` block scoped to
exactly what they need. `contents: read` is the default starting point;
widen only with a comment explaining the specific need.

This clears all five open `actions/missing-workflow-permissions`
alerts (`release.yml` was already compliant and is left untouched —
it has shipped every release this cycle and changing its token scope
risks the working release pipeline for no finding).

A CodeQL status badge is added to the README, linking to the
code-scanning dashboard, so the advisory signal is still visible at a
glance even though it doesn't gate merges.

## Consequences

**Positive**
- Workflows follow least-privilege; the finding class won't recur if
  the convention is followed for new workflows.
- ADR-0009's ~10s docs path and the solo-merge flow stay intact.
- Security signal is visible (Security tab + README badge) without
  blocking delivery.
- Zero app-code findings confirms the Kotlin codebase is clean under
  CodeQL's default query suite.

**Negative / trade-offs**
- An advisory scanner can be ignored. Mitigation: the README badge
  surfaces regressions; periodic triage of the Security tab is expected
  during release cycles.
- A genuinely dangerous finding could in theory merge before it's
  reviewed (it's not a gate). Accepted at this project's scale and
  threat model; the trigger below escalates if that changes.
- `permissions: contents: read` on `ci.yml` could break a future step
  that needs to write back to the repo or call another API — that step
  would fail loudly and the block gets widened deliberately (the point
  of least-privilege).

**Trigger to revisit**
- Make CodeQL a **required** check once: the alert baseline is zero/triaged
  AND CodeQL gains path-filtering (or we move to Advanced Setup with a
  tuned `paths-ignore`) so docs-only PRs aren't slowed. At that point
  ADR-0009's fast path and a required scanner can coexist.
- If the project gains external contributors or a wider distribution
  (Play Store / F-Droid), revisit — a required scanner is more
  justified once untrusted PRs and a real user base exist.

## Alternatives considered

**Make CodeQL required immediately.** Strongest posture, but runs on
every docs-only PR (erodes ADR-0009) and blocks the solo flow on
first-seen noise. Premature at this scale; the trigger above defers it
until it can coexist with the docs fast path.

**Advanced Setup (commit a `codeql.yml`).** Gives full control —
`paths-ignore` for docs, custom query packs, schedule tuning. More
moving parts and another workflow to maintain. Default Setup is
sufficient for now; Advanced Setup is the natural step *if* we later
make CodeQL required and need docs path-filtering.

**Suppress the permissions findings instead of fixing them.** Dismissing
the alerts as "won't fix" would hide a legitimate, cheap hardening.
Rejected — least-privilege tokens are correct regardless of CodeQL.

**Touch `release.yml` too for symmetry.** It already has
`permissions: contents: write`, wasn't flagged, and has driven every
release this cycle. Changing its token scope for cosmetic symmetry
risks the working pipeline for zero finding. Left as-is.

## References

- CodeQL Default Setup: <https://docs.github.com/en/code-security/code-scanning>
- `actions/missing-workflow-permissions` query rationale: GitHub Actions
  hardening guide — least-privilege `GITHUB_TOKEN`.
- ADR-0009 (docs-only CI bypass) — the fast path this decision protects.
- ADR-0019 (parallel jobs; the `build` aggregator that remains the sole
  required check).
