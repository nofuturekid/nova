# ADR-0020: Migrate from KSP1 to KSP2

- **Status**: Proposed
- **Date**: 2026-05-14
- **Tags**: ci, build, dependencies

## Context

The Kotlin Symbol Processing (KSP) toolchain has two parallel implementations:

- **KSP1** — the original, built around the Kotlin compiler's K1 frontend.
- **KSP2** — the K2-frontend rewrite, generally **30-50 % faster** for typical annotation-processing workloads (Hilt, Room, Compose-stable, Apollo) per the official benchmarks.

Our project uses three KSP-driven processors: Hilt (DI), the Apollo Kotlin codegen, and Compose stability inference (transitively through the Compose Compiler plugin). Combined, they account for a meaningful chunk of the assembly time on every CI run.

`gradle.properties` currently pins `ksp.useKSP2=false` with this comment:

> Hilt 2.52's KSP integration is not stable on KSP2 (PROCESSING_ERROR with "Did you forget to apply the Gradle Plugin?"). Pin to KSP1 until upstream fixes.

That note dates from when we adopted Hilt 2.52. Hilt has shipped multiple releases since (2.53, 2.54, 2.55+) and the KSP2 compatibility issue has been addressed upstream in versions ≥ 2.54 (verify exact version when implementing — the KSP2-compatible release shipped in late 2025 / early 2026 depending on which Hilt issue thread you trust).

## Decision

**Bump Hilt to the lowest version that has documented KSP2 support, then drop the `ksp.useKSP2=false` pin.**

Concretely:

1. Bump `hilt = "2.52"` in `gradle/libs.versions.toml` to the current stable release (verify ≥ 2.54 at implementation time).
2. Remove the `ksp.useKSP2=false` line from `gradle.properties` (defaults to KSP2 in current Kotlin/KSP releases).
3. Rebuild and verify on a feature branch that:
   - Hilt component generation still succeeds (build runs to completion, no `PROCESSING_ERROR`).
   - Apollo codegen still produces the expected `Query`/`Mutation`/`Data` classes.
   - Compose stability inference still classifies stable types correctly (verify by browsing recomposition diagnostics or running a quick stability inspection).
4. If verified clean, ship as a regular feature PR with its own beta cycle. ADR status flips Proposed → Accepted at that point.

The migration touches only build configuration — no source code changes — so the blast radius is contained to `gradle/libs.versions.toml`, `gradle.properties`, and possibly `app/build.gradle.kts` if KSP2 needs different processor-options syntax (it usually doesn't).

## Consequences

**Positive**

- KSP2 is faster on every clean build. Estimated savings on our specific workload (Hilt + Apollo + Compose-stable): ~30-60 s of the assemble phase, depending on incremental-cache state.
- Aligns with upstream direction — KSP1 is in long-term maintenance; new processor features land in KSP2 first.
- Removes a workaround comment that ages badly: every reader of `gradle.properties` currently has to mentally check whether the pin is still needed.

**Negative / trade-offs**

- Risk of latent KSP2 incompatibility in any of our three processors. Hilt is the historical pain point; Apollo and Compose-stable are usually fine but should be verified.
- Hilt version bump is an actual library upgrade, not just a build-toggle. Major-version-jump-style risk does not apply (Hilt stayed on 2.x), but minor-version regressions exist.
- Build-time savings depend heavily on whether the build is fully cold (largest savings) or partially incremental (smaller). On CI specifically, where each PR usually runs from a near-cold state, the savings should be visible.

**Trigger to revisit**

- If a future Hilt release drops KSP2 support (unlikely — direction is the opposite), or if an Apollo / Compose-Compiler upgrade introduces a KSP2 regression, we can re-pin via `ksp.useKSP2=false` as an escape hatch and add the bug-tracker link to the gradle.properties comment.
- If KSP3 / a new processing API ever appears, this ADR becomes the precedent for the same migration shape.

## Alternatives considered

**Stay on KSP1 indefinitely.** Lowest-risk, but trades ~30-60 s per CI run forever, and accumulates technical debt as upstreams move on. The point of this ADR is that the original blocker (Hilt 2.52 KSP2 bugs) no longer applies.

**Wait for Kotlin 2.2 / KSP3.** The Kotlin team has telegraphed a future KSP3 with further architectural changes. Waiting just defers the migration cost — KSP2 is the current-generation answer and enabling it now doesn't lock us out of KSP3 later.

**Migrate one processor at a time.** Not how KSP's `useKSP2` flag works — it's project-wide. All-or-nothing.

## References

- KSP2 release notes and benchmark numbers: <https://kotlinlang.org/docs/ksp-overview.html>
- Hilt KSP2 compatibility tracking issue: search dagger/hilt issue tracker for "KSP2 PROCESSING_ERROR" (the issue thread we originally hit).
- Related: ADR-0018 *proposed* (Gradle cache warm) and ADR-0019 *proposed* (parallel jobs). Of the three CI-acceleration ADRs, this is the highest-risk because it touches a real dependency upgrade — recommend it lands last, after 0018 and 0019 have already shaved time off without dependency changes.
