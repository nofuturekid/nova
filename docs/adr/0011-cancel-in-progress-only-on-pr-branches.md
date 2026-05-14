# ADR-0011: `cancel-in-progress` only on PR branches, not main

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: ci, release

## Context

`ci.yml` originally had:

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

This works as intended on PR branches: a second push within minutes cancels the in-flight build of the older commit. Saves runner minutes, the only build that matters is the latest anyway.

On `main` it backfires. The build-once-promote pipeline (ADR-0004) requires that **every tagged commit has a successful CI run**. The release workflow looks up that run by `head_sha` and downloads its `app-release` artifact. If a docs-only merge (ADR-0009, ~10 s) lands on `main` while a code-merge from minutes earlier is still building, `cancel-in-progress` kills the code build. The code commit then has no successful CI run, and any tag pointing at it fails the release workflow with "no successful CI run found for commit". We have to manually rerun the cancelled CI before we can ship.

This hit on the v0.1.18 ship: PR #15 (code) merged, PR #16 (docs) merged minutes later, v0.1.18 tag failed.

## Decision

Make `cancel-in-progress` conditional on the ref:

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}
```

PR branches keep the supersede behaviour; `main` is strictly sequential and unkillable.

## Consequences

**Positive**
- Releases never wait on a manual rerun for the previous-commit-was-cancelled reason.
- The invariant "every main commit's CI runs to completion (or fails for a real reason)" is reinforced — useful for any future tooling that depends on per-commit CI state.

**Trade-offs**
- Back-to-back main pushes serialise their CI. If we push to main 6 times in a row, we pay 6 × build-time. Acceptable; pushes to main are gated through PR merges, and merging 6 PRs in 10 minutes isn't normal. The docs-only bypass also keeps the common back-to-back case cheap.

**Trigger to revisit**
- If we ever stop tagging from CI artifacts (e.g. switch to build-on-tag), this carve-out becomes unnecessary — turn `cancel-in-progress: true` back on for everything.
- If main pushes start serialising long Gradle builds painfully, look at splitting CI into a fast "publish-artifact" job that runs always and a slow "lint/test" job that supports cancellation.

## Alternatives considered

- **Leave `cancel-in-progress: true` everywhere and rely on manual reruns.** What we had. Bites every time a docs merge follows a code merge.
- **Drop docs-only bypass (ADR-0009).** Brute-force fix: docs runs take 4 min, so the gap between code-merge and docs-merge gives the code CI room to finish. But this throws out the bypass benefit, which is much larger than the carve-out cost.
- **Per-commit-SHA concurrency group instead of per-ref.** Would mean no cancellation ever, since each commit has a unique SHA. Loses the PR-branch supersede behaviour we want.

## References

- `.github/workflows/ci.yml` — the `concurrency` block.
- ADR-0004 — build-once-promote pipeline that depends on per-commit success.
- ADR-0009 — docs-only bypass that exposed this issue when paired with the old cancel-in-progress rule.
