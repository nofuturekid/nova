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

## Amendment 2026-11-10 — administrators are bound by the protection

The Decision says admins are not routinely exempt and the alternatives section rejects "admin bypass" because it undermines the protection — but the live setting had `enforce_admins=false`, so the owner account (and every agent session using its credentials) could push to `main`, merge a red or out-of-date PR, or bypass `strict`. `enforce_admins` is now **on**. Break-glass means switching it off for the duration, which is a deliberate act. The same change disabled merge commits and rebase merges (squash only; ADR-0014 depends on the `(#NN)` subject). Two corrections to the record: the required check also runs with `strict` (branch must be up to date), which the release guard relies on (ADR-0014 amendment); and the API showed `dismiss_stale_reviews: true`, which has no effect with 0 required approvals.

## Amendment 2026-11-13 — rulesets and fast-forward pushes

Pull requests no longer land through a merge button. A green PR head lands by a fast-forward push of that exact commit to `main`, run by the coordinating session only (agents never merge and never push `main`). Classic branch protection is replaced by two rulesets:

- **"main (no bypass)"**: deletion, non-fast-forward, linear history, signed commits and the required status check `build` (GitHub Actions, strict). No bypass actor.
- **"main: pull request (admin push bypass)"**: the pull-request rule (0 required approvals, rebase as the only allowed merge method). The admin role may bypass it, which is what permits the push.

Only the pull-request rule is bypassable. The `build` check, linear history, signatures, non-fast-forward and deletion bind everyone, the admin included, so an admin push can neither skip CI, nor rewrite `main`, nor add an unsigned commit. The pull-request ruleset is still needed so that no other writer (an app, a bot with branch access) can push a green SHA straight to `main`.

The merge buttons are off (squash and merge commit disabled, update-branch disabled) and branches are deleted when their PR is closed by the push. Break-glass, for an emergency fix that cannot wait for CI, is to set the ruleset "main (no bypass)" to `disabled` deliberately and back to `active` afterwards.

This supersedes the 2026-11-10 sentences about `enforce_admins` and squash-only merges: classic protection is gone, and the `(#NN)` subject is no longer produced (see the ADR-0014 amendment of this date). The "GitHub Rulesets instead of classic branch protection" alternative above said to switch when ruleset features are actually needed; the feature that is now needed is the per-rule bypass, which classic protection cannot express. The history above stays as written.
