# ADR-0019: Split CI into parallel debug + release jobs

- **Status**: Proposed
- **Date**: 2026-05-14
- **Tags**: ci, performance

## Context

`ci.yml` runs every step of the build serially in a single job:

```
checkout → setup-java → setup-gradle → setup-android → keystore →
assembleDebug + lintDebug   (~2-3 min)
assembleRelease             (~1-2 min)
verify APK signature
upload 4 artifacts
```

Total wall time per code PR: ~5 minutes, of which the assemble steps account for ~4 minutes.

`assembleDebug` and `assembleRelease` are independent — they share Kotlin compilation only via the Gradle build cache, not via task ordering. The serial structure is historical, not a correctness requirement.

ADR-0014 already brought wall time down significantly by collapsing two CI runs per PR into one. Further wins from in-job optimisation (KSP2, R8 incrementality, etc.) are real but smaller. The biggest lever left is structural: stop running things serially that can run in parallel.

## Decision

**Split `ci.yml`'s single `build` job into two jobs that run in parallel: `build-debug` and `build-release`. Both share an upstream `setup` job whose outputs are restored via cache.**

Concretely:

```yaml
jobs:
  setup:
    # Detect docs-only, fail fast if no code changed.
    # Otherwise emit `code=true` output.
  build-debug:
    needs: setup
    if: needs.setup.outputs.code == 'true'
    steps:
      - checkout
      - setup-java
      - setup-gradle (cache-read-only as today)
      - setup-android
      - assembleDebug + lintDebug
      - upload debug APK + lint report
  build-release:
    needs: setup
    if: needs.setup.outputs.code == 'true'
    steps:
      - checkout
      - setup-java
      - setup-gradle
      - setup-android
      - decode keystore (skipped if KEYSTORE_B64 missing)
      - assembleRelease
      - verify APK signature
      - upload release APK + mapping
```

Wall time becomes `setup + max(debug, release) ≈ 30 s + 3 min = ~3.5 min` instead of `setup + debug + release ≈ 30 s + 4 min = ~4.5 min`. Realistic savings: 60-90 seconds per PR.

The setup phase (checkout + JDK + Gradle + Android SDK) runs twice — but each instance benefits from the warm cache populated by ADR-0018 *proposed*, so the duplicate setup costs ~30 s rather than the cold ~90 s.

Branch protection's required check (`build`) needs replacement. Two options:

1. Make both `build-debug` and `build-release` required individually.
2. Add a third job `build` that depends on both and is a trivial pass/fail aggregator (`needs: [build-debug, build-release]`, single `run: echo done`). Branch protection requires only `build`.

Option 2 keeps branch-protection config stable and lets us add/remove parallel jobs in the future without touching repo settings. Pick option 2.

## Consequences

**Positive**

- ~60-90 s wall time saved per code PR, on top of ADR-0018's cache savings. Combined with cache warming, expect total CI down from ~5 min to ~3-3.5 min.
- The split reflects reality: debug build doesn't depend on release build, lint doesn't depend on either, signature verification only needs the release APK. The DAG matches the graph instead of squashing it into a line.
- Future jobs (e.g. instrumentation tests, dependency scanning) drop into the same parallel structure without serialising.

**Negative / trade-offs**

- CI minutes consumed roughly doubles (two jobs instead of one). Free for OSS on GitHub-hosted runners; would be a real cost on self-hosted or paid runners.
- The `setup-android` step in particular pays its own startup cost twice. Without ADR-0018's warm cache the doubling could overwhelm the parallelism savings; the two ADRs need to land together (or 0018 first).
- Adds workflow complexity. Currently one job, easy to read top-to-bottom. After: 2-3 jobs with shared inputs, output passing, an aggregator. Worth it for the savings.
- Two artifact-upload steps' worth of duplication risk: if an artifact name appears in both jobs, `actions/upload-artifact@v6` rejects the second upload. The current four artifacts split cleanly between the two jobs (debug+lint to `build-debug`; release+mapping to `build-release`), so this is fine — but worth a comment in the workflow.

**Trigger to revisit**

- If wall time still exceeds ~3 min on a warm cache, KSP2 (ADR-0020 *proposed*) becomes the next target — annotation processing is then the bottleneck, not parallelism.
- If we ever need a build artifact from `build-debug` to be consumed by `build-release` (or vice versa), the parallelism fundamentally breaks. Re-evaluate then.

## Alternatives considered

**Run lint as its own parallel job, leave assemble serial.** Smaller win (lint is ~30-60 s) for half the structural complexity. The numbers don't justify it once we're touching the workflow at all.

**Keep one job, switch to a faster runner.** GitHub's larger-hosted runners (8-core) cost extra. For an OSS project on the free tier, not worth it. Doesn't address the underlying serial-DAG mismatch either.

**Just turn on Gradle's parallel project execution flag.** Already on (`org.gradle.parallel=true` in `gradle.properties`). It parallelises *within* a single Gradle invocation but doesn't parallelise across `assembleDebug` and `assembleRelease` invocations because we issue them as separate `./gradlew` calls.

**Combine `assembleDebug` and `assembleRelease` into a single Gradle invocation: `./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug`.** Gradle would then schedule them with maximum overlap. Solid intermediate option — easier to implement than full parallel jobs, captures most of the benefit. Worth doing as a step before this ADR if implementation time is tight; this ADR's full split is a more general structure.

## References

- ADR-0014 — Single CI run per PR (the prior pipeline-shape decision).
- ADR-0018 *proposed* — Gradle cache warm on main (depends-on / synergy).
- ADR-0020 *proposed* — KSP1 → KSP2 migration (orthogonal but complementary).
- GitHub Actions docs on job dependencies + outputs: <https://docs.github.com/en/actions/using-jobs/using-jobs-in-a-workflow>
