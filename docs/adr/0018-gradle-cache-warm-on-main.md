# ADR-0018: Populate the Gradle build cache on push to main

- **Status**: Proposed
- **Date**: 2026-05-14
- **Tags**: ci, performance

## Context

CI wall time per code PR is currently ~5 minutes, with the largest single contributor being `assembleDebug` + `assembleRelease`. We use `gradle/actions/setup-gradle` for a remote Gradle build cache, but its current configuration writes nothing to that cache.

Two earlier decisions interact unhelpfully:

1. **ADR-0014** restricted `ci.yml` to `pull_request` and `workflow_dispatch` triggers — `push: main` no longer fires the workflow, because the squash-merge would just rebuild what the PR-CI built minutes earlier under a different SHA.
2. The `setup-gradle` step is configured with `cache-read-only: ${{ github.event_name == 'pull_request' }}` — PR builds are read-only against the cache.

The combined effect: `push: main` doesn't run, so there's no event that *writes* to the cache. PR builds *only* read. The cache stays empty forever, and every PR build starts from a cold Gradle daemon and fetches all dependencies from scratch.

The intent behind the read-only flag was correct — fork PRs from untrusted contributors shouldn't be able to poison the cache for the project. But the trigger restriction introduced after the flag broke its preconditions, and the configuration drifted into a useless state.

## Decision

**Populate the Gradle cache from a small dedicated workflow that runs on `push: main`.**

A new file `.github/workflows/cache-warm.yml`:

- Triggers on `push: main`
- Runs the same `actions/checkout@v5` + `actions/setup-java@v5` + `gradle/actions/setup-gradle@v5` setup steps as `ci.yml`
- Uses `cache-read-only: false`
- Runs a single low-cost task that hits the entire dependency graph and primes the daemon: `./gradlew help` (downloads + resolves everything without compiling) or `./gradlew :app:compileDebugKotlin --dry-run` for slightly more coverage
- Skips the actual APK assembly (no `assembleDebug` / `assembleRelease`), since that already ran during the PR's CI run minutes earlier

`ci.yml` stays unchanged: `cache-read-only: ${{ github.event_name == 'pull_request' }}` continues to protect the cache from PR-write pollution. PR builds *read* the warm cache populated by `cache-warm.yml`.

The warmer is cheap — typically 30-90 s end-to-end and uses only Gradle's resolve/download phase, no compilation or KSP. CI cost: roughly 1-2 minutes of free runner time per merged code PR, in exchange for ~30-60 s saved on every subsequent PR build.

## Consequences

**Positive**

- PR builds get a warm Gradle cache: dependency-resolution and the Gradle daemon's plugin classpath come from cache rather than fresh download. Realistic savings: 30-60 s of wall time per PR.
- The savings compound with the parallel-jobs change in ADR-0019 *proposed* (parallel jobs each pay the setup cost once; cache cuts that cost).
- No change to the cache-read-only safety property for fork PRs — they still can't write.

**Negative / trade-offs**

- One extra workflow file to maintain, one extra job's worth of CI minutes per merged PR. For OSS projects on GitHub-hosted runners that's free; for self-hosted runners it'd be a small recurring cost.
- The warmer can race with PR builds: if a PR build starts moments before main is updated, it'll still see the old cache. Acceptable — caches are advisory, not correctness-critical.
- Adds a second source of truth for "how do we set up a Java + Gradle environment". Should be kept in lockstep with `ci.yml` (an inline comment in both files saying so is enough).

**Trigger to revisit**

- If cache-warm.yml's runtime drifts above 2 minutes consistently, the cost-benefit tilts and we should re-examine.
- If GitHub introduces a way for the original `ci.yml` to *also* write to a shared cache scope from PR runs without exposing fork PRs to write, this whole workaround becomes redundant.

## Alternatives considered

**Re-enable `push: main` for `ci.yml`.** Would let the post-merge run write the cache. Rejected: directly conflicts with ADR-0014's reasoning — we'd be doing the exact rebuild ADR-0014 saved us from, just for the side-effect of cache writes.

**Drop `cache-read-only` so PR builds also write.** Simplest config change. Rejected: defeats the cache's protection against pollution from untrusted PRs (today the project is solo, but the protection should be future-proof). Also: PR-cache writes are scoped to the PR's branch, not main, so they'd populate per-branch caches that don't help across PRs anyway.

**Pre-build a Docker image with the Android SDK, Gradle, plugins resolved.** Significant savings but adds a Docker image-maintenance burden — every build-tools / AGP upgrade requires rebuilding the image. Defer until cache-warming is no longer enough.

**Self-hosted runner with persistent disk.** Could keep the Gradle cache hot on disk between runs. Rejected: significant operational burden for a small app; the cache strategy here gets most of the benefit at zero operational cost.

## References

- ADR-0014 — Single CI run per PR (the reason `ci.yml` no longer fires on `push: main`).
- `gradle/actions/setup-gradle` cache documentation: <https://github.com/gradle/actions/blob/main/docs/setup-gradle.md#caching>
- Related: ADR-0019 *proposed* (parallel debug + release jobs) and ADR-0020 *proposed* (KSP1 → KSP2). The three CI accelerations stack: cache reduces setup time, parallelism reduces wall time, KSP2 reduces annotation-processing time.
