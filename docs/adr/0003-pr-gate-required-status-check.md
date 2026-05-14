# ADR-0003: PR-gate workflow with required CI status check on main

- **Status**: Accepted
- **Date**: 2026-05-13
- **Tags**: ci, process

## Context

The repo accepts contributions from the maintainer and (potentially) external collaborators. Direct pushes to `main` were initially possible, which meant a broken build could land without anyone noticing until release time. As soon as collaboration was on the table, we needed a hard gate.

We also wanted CI to run on **every** proposed change, not just on a merge — so reviewers and contributors see green/red before merging, not after.

## Decision

- **Branch protection on `main`** with:
  - PRs required for all changes (no direct pushes, even from admins, are routine — we use the protection rule, not goodwill).
  - **Required status check**: the `build` job from `ci.yml`. PRs cannot be merged until it returns success on the head commit.
  - Stale-review-dismiss disabled (the project doesn't require review approval — see below).
- **No mandatory review** — solo dev plus occasional contributors. Requiring an approving review would block all merges when the dev is the only person around.
- **CI triggers**: `push` to `main` + every `pull_request` + manual `workflow_dispatch`.

## Consequences

**Positive**
- No broken build can land on `main`.
- The `build` check name (`build`) is stable across the project's history; renaming the job would silently un-protect the branch until protection is updated. Documented in ADR-0009.
- External contributors can fork-PR; CI runs against their fork (with `KEYSTORE_B64` unavailable, falling back to debug-only build — see `ci.yml`).

**Trade-offs**
- CI must pass before merge → docs-only PRs that don't need a build still wait on it. Solved by ADR-0009 (docs-only bypass that still reports success on `build`).
- "No required review" means the dev can self-merge their own PRs. Acceptable for a hobby project; trade safety for velocity.

**Trigger to revisit**
- If the project grows past 2–3 active contributors, add mandatory reviews.
- If we ever rename the `build` job, update branch protection in the same PR.

## Alternatives considered

- **No branch protection, rely on convention.** Worked for a week; first time someone forgot the PR step we knew we needed enforcement.
- **Required review with admin bypass.** Reviews-as-friction is fine; reviews-as-blocker-when-no-reviewer-is-around isn't, and admin bypass undermines the protection.
- **GitHub Rulesets instead of classic branch protection.** Same effect, more flexible syntax. Classic protection works and is well-understood; switch when we actually need ruleset features.

## References

- `.github/workflows/ci.yml` — the `build` job is the gating check.
- ADR-0004 — build-once-promote pipeline depends on this gate.
- ADR-0009 — docs-only bypass keeps the gate fast.
