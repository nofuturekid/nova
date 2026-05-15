# ADR-0023: Upgrade to AGP 9 + modern Gradle/Kotlin/Hilt/Compose/Apollo toolchain

- **Status**: Accepted
- **Date**: 2026-05-15
- **Tags**: build, ci, dependencies

## Context

The build was pinned to AGP 8.9.2 / Gradle 8.11.1 / Kotlin 2.0.21 / KSP
2.0.21-1.0.27 / Hilt 2.55 / Compose BOM 2024.12.01 / Apollo 4.1.1.
ADR-0020 (KSP2 migration) deliberately held Hilt at 2.55 and stated the
upgrade would happen "once AGP 9 / a newer Kotlin lands" — Hilt 2.56+
requires Kotlin ≥ 2.1.10 and Hilt 2.59.2's tooling targets AGP 9. **This
upgrade is exactly the unblock event ADR-0020 named.**

Forces in play:

- **AGP 9 is a hard, coupled jump.** AGP 9 requires Gradle ≥ 9.1.0 and
  JDK ≥ 17, has a runtime dependency on Kotlin Gradle Plugin 2.2.10
  (auto-upgrading lower), and enables **built-in Kotlin support** by
  default — `org.jetbrains.kotlin.android` is no longer applied. The
  opt-out of the new model is removed in AGP 10 (H2 2026, not yet
  released), so adopting the built-in-Kotlin DSL now makes AGP 10 a
  non-event rather than a forced scramble.
- **Apollo 4.1.1 cannot run on the new toolchain.** Apollo Kotlin 5.0.0
  is the release built for KGP 2.3 / Gradle 9 / AGP 9; 4.1.1 is not
  toolchain-compatible. Apollo 5 is therefore *load-bearing*, not a
  deferrable secondary bump. It keeps the `com.apollographql.apollo`
  package; the codebase uses only stable core APIs (`ApolloClient`,
  `Query`, `ApolloException`, `query/mutation/execute`, `hasErrors`,
  `okHttpClient`) and none of Apollo 5's removed surfaces (normalized
  cache, data builders, `ApolloIdlingResource`, `PackageNameGenerator`),
  so for this app it is a version-only bump.
- GitHub Actions pins had drifted behind current major tags.
- Constraints that bound *how* this ships, not *whether*: ADR-0018
  (ci.yml ↔ cache-warm.yml lockstep), ADR-0014 (release.yml resolves
  the CI artifact via the squash-merge PR number — trigger logic must
  not move), ADR-0006/0013/0015 (a non-docs change needs a version bump
  and a higher-risk infra change goes through a beta), ADR-0022
  (precedent: treat the working release.yml conservatively).
- The sandbox cannot build locally (no Android SDK; only GitHub is
  network-reachable, so Gradle/Maven downloads fail). **CI is the only
  verification harness**, which argues for bundling everything
  unavoidably load-bearing into one PR rather than discovering breakage
  across expensive cold-cache iterations.

## Decision

Upgrade the toolchain to **AGP 9.2.0 / Gradle 9.5.1 / Kotlin 2.3.21 /
KSP 2.3.8 / Hilt 2.59.2 / Compose BOM 2026.05.00 / Apollo 5.0.0 / JDK
21**, adopting AGP 9's built-in-Kotlin DSL now.

Kotlin is taken to the current stable **2.3.21** (not merely AGP 9's
2.2.10 floor): the project tracks current tooling and AGP 9.2 supports a
newer KGP than its bundled minimum. JDK is moved to the current LTS
(**21**); AGP 9 only requires ≥ 17, and this has no app/device impact
(minSdk stays 26, same signed APK, in-place self-update intact — it is
purely a build-toolchain choice).

Built-in-Kotlin DSL changes: drop `org.jetbrains.kotlin.android` from
the version catalog and both build scripts; keep `kotlin.compose` and
`kotlin.serialization` (separate Kotlin compiler plugins) plus `ksp`,
`hilt`, `apollo`; relocate the `kotlin { jvmToolchain }` block out of
`android {}` to the top-level `kotlin {}` extension and set it to 21;
move `compileOptions` source/target to `VERSION_21`. The now-dead
`android.suppressUnsupportedCompileSdk=36` is removed (AGP 9.2 supports
compileSdk 36/37 natively) and the stale ADR-0020 Hilt-pin comment in
`gradle.properties` is rewritten.

Shipped as **three sequenced PRs in one `0.1.28-beta` cycle**, each
green+merged before the next opens, each its own beta per
ADR-0006/0013/0015:

1. **PR-1 — `0.1.28-beta1` (vc 37):** the load-bearing set — Gradle
   wrapper, AGP, Kotlin, KSP, Hilt, Compose BOM, **Apollo 5**, JDK 21,
   built-in-Kotlin DSL migration, **and the ci.yml + cache-warm.yml
   `setup-java` bump to JDK 21**, this ADR. The CI JDK bump *must* ride
   with PR-1: PR-1's build targets a Java 21 toolchain
   (`jvmToolchain(21)`/`VERSION_21`), so a JDK-17 CI runner cannot
   compile it (no foojay auto-provisioner is configured). This was a
   latent ordering bug in the original plan (JDK-21 CI was slated for
   PR-2) — caught by PR-1's first CI run failing at "Assemble debug"
   with JDK 17 still installed. One atomic commit (there is no green
   intermediate between old-AGP/new-Gradle and new-AGP/old-Gradle, nor
   between a 21-targeting build and a 17 runner; a split buys nothing
   and keeps `git bisect` honest). ci.yml ↔ cache-warm.yml stay in
   lockstep per ADR-0018.
2. **PR-2 — `0.1.28-beta2` (vc 38):** GitHub Actions *version-pin*
   refresh only (JDK already moved to PR-1) — ci.yml + cache-warm.yml in
   lockstep (checkout v6, gradle/actions v6), release.yml treated
   conservatively (checkout v6, download-artifact v8, action-gh-release
   v3) with input compatibility verified before merge.
3. **PR-3 — `0.1.28-beta3` (vc 39):** remaining catalog libraries
   (coroutines, serialization, OkHttp, Coil, AndroidX, nav, datastore,
   …) refreshed to latest stable — isolated so a regression here is
   attributable separately from the toolchain.

## Consequences

**Positive**

- Unblocks Hilt 2.59.2 — the ADR-0020 follow-through is complete.
- Current-generation toolchain; KGP bundled with AGP/Gradle reduces
  version-skew risk; configuration cache is now the Gradle-preferred
  path (already enabled here).
- The built-in-Kotlin DSL is adopted now, so AGP 10 (which removes the
  opt-out) becomes a non-event instead of a forced migration.
- Apollo 5's classloader-isolated Gradle plugin and modern runtime, for
  a version-only change in this codebase.

**Negative / trade-offs**

- PR-1 is large and high-risk (toolchain + DSL + Apollo major), and the
  sandbox can only verify it through CI — cold-cache, ~5+ min/iteration.
  Mitigated by front-loading all compatibility research and the beta
  gate before any device sees it.
- One-time cold Gradle cache after PR-1 (wrapper/AGP/Kotlin change the
  cache key; PR builds are cache-read-only). cache-warm.yml re-warms on
  push to main post-merge.
- **Hilt rollback is coupled to the whole toolchain**: Hilt 2.55 is
  incompatible with Kotlin 2.3.21, so Hilt cannot be reverted in
  isolation — only a full atomic toolchain revert (hence the single
  PR-1 commit) or a forward-fix to a Kotlin-2.3-compatible Hilt.
- AGP 9 `compileOptions`/`kotlinOptions` deprecation warnings now accrue
  toward the AGP-10 forced cleanup.
- Three coordinated beta releases instead of one.

**Trigger to revisit**

- AGP 10 ships (H2 2026): the built-in-Kotlin DSL is already adopted, so
  this becomes DSL polish, not a migration — this ADR is the precedent.
- Any regression in Apollo / Hilt / Compose codegen or stability under
  KSP2 + Kotlin 2.3.x → escalate per the rollback coupling above.

## Alternatives considered

- **Defer the built-in-Kotlin DSL (keep classic plugin application).**
  The Plan agent's default recommendation. Rejected: AGP 10 removes the
  opt-out regardless, so deferring just buys a second forced migration
  later. Doing it once, now, with the toolchain jump is cheaper overall.
- **Apollo 5 as a separate pre-toolchain PR.** Rejected: Apollo 5 wants
  Gradle 9 / AGP 9, so migrating it on the old toolchain risks a wasted
  cycle; folding it into PR-1 is the only coherent ordering.
- **One mega-PR for everything.** Rejected: the single `build`
  aggregator gate gives no which-broke signal; the secondary-library
  refresh is genuinely isolatable and belongs in its own PR.
- **Stay on AGP 8.** Rejected: blocks Hilt 2.59.2 indefinitely,
  accumulates upstream drift, and contradicts the ADR-0020 plan that
  explicitly named this as the unblock.
- **Keep JDK 17.** Rejected: the project tracks current tooling, 21 is
  the current LTS, AGP 9.2 fully supports it, and there is zero
  app/device-compatibility impact.

## References

- AGP 9.0/9.2 release notes; "Migrate to built-in Kotlin" (Android
  Studio docs); Gradle 9.5 release notes; Kotlin 2.3 release notes; KSP
  releases; Hilt/Dagger 2.59.2; Apollo Kotlin 5 migration guide
  (`apollographql.com/docs/kotlin/v5/migration/5.0`).
- ADR-0020 (KSP2 migration; the Hilt pin this upgrade unblocks).
- ADR-0018 (ci.yml ↔ cache-warm.yml lockstep).
- ADR-0014 (release.yml PR-number artifact resolution — untouched).
- ADR-0006 / ADR-0013 / ADR-0015 (beta-first + version-bump policy).
- ADR-0022 (precedent for treating the working release.yml
  conservatively).
