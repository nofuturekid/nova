# ADR-0015: Promotion to a different version-string requires a new commit

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: release, data
- **Supersedes** (in part): ADR-0006 (the "same commit, different tag" promotion flow).

## Context

ADR-0006 defined the beta → rc → stable promotion as "tag the same commit again with the new tag" (e.g. `v0.1.20-beta1` and `v0.1.20` both pointing at SHA `2e84cb9`). ADR-0004 then has `release.yml` reuse the existing CI artifact for the new tag — no rebuild, no source change. Fast, frictionless.

ADR-0013 fixed a separate bug: the APK's `versionName` must include the pre-release suffix (e.g. `0.1.20-beta1`), otherwise the in-app updater compares versions wrong and can't offer beta → beta transitions.

The two ADRs together create an unsolvable conflict for the **beta → stable** transition:

- An APK built from a commit with `versionName = "0.1.20-beta1"` carries that string in `BuildConfig`.
- Re-tagging the same commit as `v0.1.20` doesn't change the APK contents — only the tag name and file name.
- A user on `v0.1.20-beta1` (`versionCode = 20`) sees the `v0.1.20` release, the in-app updater offers it, the user taps Install, the APK downloads — but Android's `PackageInstaller` sees `versionCode = 20` (same as installed) and the install is a no-op. The user is stuck.

Hit live with v0.1.20-beta1 → v0.1.20. The stable APK on GitHub was byte-identical to the beta1 APK.

## Decision

**Each tag must correspond to a distinct commit with its own `versionName` and a strictly higher `versionCode`.** No more "same commit, different tag" for any promotion that changes the version-string.

Concretely, the promotion flow becomes:

```
ship beta1   →  PR bumps to "0.1.21-beta1"/code=21  →  tag v0.1.21-beta1
test on device
ship beta2   →  PR bumps to "0.1.21-beta2"/code=22  →  tag v0.1.21-beta2
test on device
ship stable  →  PR bumps to "0.1.21"     /code=23   →  tag v0.1.21
```

The "promotion PR" is typically a single-file diff in `app/build.gradle.kts` (two lines: `versionCode`, `versionName`). It still goes through the normal PR → CI → squash-merge → tag flow.

For purely metadata-only promotion PRs the `versionCode + versionName` bump qualifies as a "single-file fix the dev has verified" — direct-stable allowed per ADR-0006's allowance list. No nested beta-first required (the change has nothing to test).

## Consequences

**Positive**
- Every published APK on GitHub Releases is a real, upgradable Android package: `versionCode` strictly increases across all tags (stable + pre-release intermixed).
- The in-app updater works correctly for *every* transition, including beta → stable in the same numeric tuple.
- `BuildConfig.VERSION_NAME` always matches the tag name → diagnostics (a user reports "I'm on 0.1.20-beta1") become unambiguous.

**Trade-offs**
- One extra PR cycle per release phase (beta1 → beta2 → rc1 → stable = 3 promotion PRs + the original feature PR = 4 builds for a "full" cycle). Mitigated by: most ships skip rc and most don't iterate beta past beta1; in practice it's usually 1-2 promotion PRs.
- `versionCode` numbers consume faster. Trivial — it's an Int, we won't run out.
- The "build-once-promote" pipeline (ADR-0004) is still in effect *within* a release phase — each tag still reuses the CI artifact for *its* commit. Only the cross-phase "re-tag same commit" shortcut is gone.

**Trigger to revisit**
- If we ever switch to a release model where the same APK can ship under multiple tags (e.g. publishing to multiple channels), revisit.
- If we add a Gradle task that mutates `versionName` at release time from the tag (`-PversionNameOverride=...`) and have `release.yml` rebuild on stable tags, this ADR can be relaxed. Considered as alternative below; rejected for complexity.

## Alternatives considered

- **Same commit, different tag (status quo per ADR-0006)** — broken for upgraders, as documented above.
- **`release.yml` rebuilds with `versionName` overridden for stable tags.** Possible: detect "tag is stable + previous tag on this commit was a beta" → run `./gradlew :app:assembleRelease -PversionNameOverride=0.1.20` with a Gradle property → produce a new APK on the fly. Adds substantial logic to `release.yml`, makes it a real builder again (defeats the "release just promotes" spirit of ADR-0004), and requires a Gradle property hook in `build.gradle.kts` for the override. Rejected as too much machinery for the cleaner "new commit per phase" rule.
- **Drop ADR-0013 (versionName never includes suffix).** Goes back to the old broken state where `BuildConfig` lied about its own version. Rejected — diagnostics matter more than the promotion shortcut.

## References

- `app/build.gradle.kts` — the `versionCode` and `versionName` lines that change per phase.
- ADR-0004 — build-once-promote; still applies within a phase.
- ADR-0006 — promotion-flow bullets updated to reflect this rule.
- ADR-0013 — versionName-with-suffix rule that, in combination with the old promotion flow, produced the bug.
- v0.1.20 / v0.1.20-beta1 → v0.1.21 transition — the case that surfaced this.
