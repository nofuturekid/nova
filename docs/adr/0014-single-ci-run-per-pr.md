# ADR-0014: Single CI run per PR — release workflow resolves through PR

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: ci, release

## Context

Before this ADR, `ci.yml` triggered on three events: `pull_request`, `push: branches: [main]`, and `workflow_dispatch`. Every code PR therefore produced **two** full builds (~5 min each):

1. **PR-CI** on the PR head SHA — satisfies the `build` required status check (ADR-0003), validates the change.
2. **main-CI** on the squash-merge commit SHA — produces the artifact tied to the main commit, so `release.yml` could find it via `head_sha` lookup when a tag was pushed (ADR-0004).

The two builds compile the exact same source. They differ only in their commit SHA: GitHub's "Squash and merge" creates a new commit on main whose tree is identical to (or a clean folding of) the PR-branch tree, but with a fresh SHA. That fresh SHA is the only reason the second build existed — `release.yml`'s artifact lookup keyed on it.

User observation that prompted this ADR: "die grosse Pipeline lief 2x — das nervt." Correct — ~5 minutes wasted per code PR for an artifact byte-identical to one we already had.

## Decision

**`ci.yml` no longer runs on `push: branches: [main]`.** Triggers reduce to `pull_request` + `workflow_dispatch`.

**`release.yml` resolves the artifact through a two-path lookup** when a tag is pushed:

1. **Direct path**: query the GitHub Actions API for a successful `ci.yml` run whose `head_sha` equals the tagged commit. Hits when someone manually invoked `gh workflow run ci.yml --ref <SHA>` on the main commit — useful for backfill / direct-push recovery.
2. **PR path** (the normal case): parse `(#NN)` from the tagged commit's subject line (GitHub's standard squash-merge format), call `gh pr view NN --json headRefOid` to get the PR's head SHA, then find the CI run for that SHA. Use that run's artifact.

If neither path resolves, `release.yml` errors out with the recovery command printed in the failure log:

```
gh workflow run ci.yml --ref <SHA>
```

## Consequences

**Positive**
- One CI run per code PR instead of two. Saves ~5 minutes per ship on cold cache.
- No source change, no functional change — the artifact is the same one that already passed the PR-CI gate. We just rewired who finds it.
- Branch protection's required check (the PR-CI `build` job) is unchanged; the gate is unaffected.
- Docs-only PRs (ADR-0009) keep their fast bypass on PR-CI; nothing further to bypass because main no longer runs CI.

**Trade-offs**
- `release.yml` carries more logic. Recovery path for "direct push to main without a PR" (admin emergency) is manual: `gh workflow run ci.yml --ref <SHA>` then push the tag again. Mitigated by the failure log printing this exact command.
- Path-2 lookup relies on `(#NN)` in the commit subject. GitHub's squash-merge always appends this; rebase-merge does not. We use squash-merge consistently (project policy); switching would break the lookup until release.yml is taught the new format.
- ADR-0011 (`cancel-in-progress` only on PR branches, not main) becomes partially moot: with no main runs, the carve-out has nothing to protect on main. It still applies to manual `workflow_dispatch` runs on main, so the rule stays. `concurrency.cancel-in-progress: true` is now safe globally because the only events left are `pull_request` (where supersede is desired) and `workflow_dispatch` (rare, manual — supersede is fine if user re-triggers).

**Trigger to revisit**
- If we switch to rebase-merge (loses `(#NN)` in commit message), Path 2 breaks — update the parser.
- If direct-pushes to `main` become routine (e.g. we drop branch protection), Path 2 also breaks — fall back to mandating Path-1 manual builds, or restore push:main on ci.yml.
- If tests grow long enough that we want continuous "is main healthy?" signal independent of release-time, re-add a lightweight main-CI that runs the test suite only (no APK rebuild).

## Alternatives considered

- **Status quo** (rejected by user as wasteful — exactly the prompt for this ADR).
- **Smart `ci.yml` on push:main: detect (#NN) and download+re-upload PR artifact instead of rebuilding** (~30 s instead of 5 min). Same savings as the chosen approach, but adds ~30 lines of artifact-copy logic to ci.yml. Rejected because release.yml is the natural place for "find the artifact for this tag" — moving the lookup there keeps ci.yml purely about validation.
- **Switch from squash-merge to merge-commit (linear preserved via rebase-and-merge or fast-forward).** A merge commit's `^2` parent IS the PR head SHA, so `release.yml` could find the artifact via parent SHA without parsing commit messages. Rejected because squash-merge keeps a clean linear history of one-commit-per-feature, which we value more than the simpler parent lookup.
- **Reduce PR-CI to debug-only (no `assembleRelease`).** Saves ~2 min on PR-CI but still leaves the main-CI doing the full build — net less savings, plus R8/ProGuard issues would only surface on main, not in PR review.

## References

- `.github/workflows/ci.yml` — `on:` trigger block.
- `.github/workflows/release.yml` — `Find CI artifact for this commit` step.
- ADR-0003 — PR-gate invariant that this preserves.
- ADR-0004 — build-once-promote pipeline that this refines.
- ADR-0009 — docs-only bypass (still in PR-CI).
- ADR-0011 — `cancel-in-progress` rule; partially moot now but kept for `workflow_dispatch`.

## Amendment 2026-11-10 — release guard, trusted-run lookup, recovery command

A CI review found that the lookup above binds artifact to tag only indirectly (by `head_sha` or by the `(#NN)` subject), and that the printed recovery command does not work. Three changes to `release.yml`:

- **Recovery command.** `gh workflow run --ref` accepts a branch or tag name, not a SHA, so every `gh workflow run ci.yml --ref <SHA>` above (Decision, Trade-offs, the failure log) reads `gh workflow run ci.yml --ref <tag>`. The dispatch run's `head_sha` is then the tagged commit and the direct path hits.
- **Trusted-run lookup.** Both paths select the newest successful `ci.yml` run for the head SHA that is a `pull_request` from this repository or a `workflow_dispatch` **and** still has the `app-release` artifact. A fork PR can reuse a head SHA but never produces the artifact, so it can no longer shadow the real run.
- **Release guard** (`.github/scripts/release-guard.sh`, run before download/publish). It fails the release unless: the tagged commit is an ancestor of `origin/main`; on the PR path the PR is `MERGED` with `mergeCommit == tagged SHA`; the tree of the CI run's head commit equals the tagged commit's tree (holds under `strict` branch protection); `v<versionName at SHA> == tag` (the pre-release suffix is part of `versionName`, ADR-0013); and `versionCode at SHA` is greater than at the previous `v*` tag (ADR-0015). It is the mechanical form of the "release can only ship what CI built from this commit" invariant (ADR-0004); `v0.1.40-beta1` shipped a `0.1.39` build under a `0.1.40-beta1` tag before the guard existed.

- **Recovery without re-pushing tags.** With `refs/tags/v*` protected against update/deletion, "then push the tag again" no longer works. Recovery is: re-run the failed release run (`gh run rerun <run-id>`); for a missing or expired artifact run `gh workflow run ci.yml --ref <tag>`, wait for it, then re-run the release run. A guard failure on a version mismatch uses up that tag; fix forward with a new commit and the next version. The guard also rejects tags that are not `vX.Y.Z` or `vX.Y.Z-(alpha|beta|rc|pre)N`, so a digitless pre-release tag can no longer be published as stable (ADR-0005).

The same change set made squash the only allowed merge method on `main` (the `(#NN)` subject now always exists) and protects `refs/tags/v*` against update and deletion, which closes the "revisit" trigger about merge methods above.

## Amendment 2026-11-10 (2) — the APK is the source of truth

The guard above reads `versionName`/`versionCode` by parsing the text of `app/build.gradle.kts`. That is not what Gradle evaluates: a flavor/buildType override, `versionNameSuffix` or a computed value can make the guard approve a tag while the shipped APK carries another version (the `v0.1.40-beta1` failure class). The source check stays as the early, cheap gate (and now refuses a file that assigns `versionName`/`versionCode` more than once with different values), but `release.yml` additionally runs `.github/scripts/release-verify-apk.sh` after the artifact download and before anything is published. It reads the downloaded APK with `aapt2 dump badging` and `apksigner verify --print-certs` (build-tools of the runner's Android SDK, highest version) and fails unless: the package is `io.github.nofuturekid.nova` (the `direct` flavor has no `applicationIdSuffix`); `v<versionName> == tag`; `versionCode` equals the source-derived value at the tagged commit that the guard already required to exceed the previous tag's (so source and artifact must agree and the ratchet carries over); and the APK has exactly one signer whose certificate SHA-256 equals the digest pinned in the script (taken from the published `v0.1.41` APK; a key rotation must update it). The previous release's APK `versionCode` is not fetched: the source-derived ratchet is sufficient and keeps the step offline.
