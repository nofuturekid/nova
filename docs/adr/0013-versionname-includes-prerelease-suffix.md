# ADR-0013: `versionName` includes the pre-release suffix

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: release, data

## Context

The pre-release tag convention (ADR-0005) puts the suffix in the **tag** name: `v0.1.19-beta1`. We had been setting `versionName` in `app/build.gradle.kts` to just the numeric form (`0.1.19`) regardless of whether the tag was a beta or a stable, treating the suffix as "release-side metadata only".

The in-app updater (ADR-0008) reads `BuildConfig.VERSION_NAME` and compares it via SemVer against GitHub release tags. The comparator says "stable beats pre-release at the same numeric tuple" (`SemVer.compareTo` in `UpdateRepository.kt`).

Consequence: a device running v0.1.19-beta1 — but whose APK was built with `versionName = "0.1.19"` — reports itself as **stable**. When v0.1.19-beta2 is later published (tag `v0.1.19-beta2`, prerelease=true), the comparator says current (stable `0.1.19`) > target (pre `0.1.19-beta2`) and **no update is offered**. The user can't move forward within the same numeric tuple via the in-app updater.

We hit this exactly: while polishing the in-app installer flow (button label with version, relative-date display), we tried to ship v0.1.19-beta2 and the installed v0.1.19-beta1 wouldn't see it. The release for beta2 was real but unreachable from the very screen we were trying to validate.

## Decision

`versionName` in `app/build.gradle.kts` must match the **tag's full form**, including any pre-release suffix.

- Stable release `vX.Y.Z` → `versionName = "X.Y.Z"`
- Pre-release tag `vX.Y.Z-beta1` → `versionName = "X.Y.Z-beta1"`
- Pre-release tag `vX.Y.Z-rc2` → `versionName = "X.Y.Z-rc2"`

The promotion beta → rc → stable means an explicit `versionName` bump (drop or replace the suffix) committed before tagging the next phase.

`versionCode` continues to increase monotonically across every shipped release regardless of class.

## Consequences

**Positive**
- `BuildConfig.VERSION_NAME` now matches what the user installed. The in-app updater's SemVer comparison gives the right answer for every transition (beta → beta, beta → rc, beta → stable, …).
- The release policy (ADR-0006) and the data model line up — both treat pre-releases as a distinct version, not a stable-with-a-tag-on-the-side.

**Trade-offs**
- One more line to touch per release. Manageable: the version bump is already a deliberate step.
- A one-time legacy: v0.1.19-beta1 was built with the old wrong `versionName = "0.1.19"`. Anyone who installed that build can't auto-update **within** the 0.1.19 numeric tuple via the in-app updater. They'd need a manual install (browser → GitHub Releases → APK) once, or wait for any version with a higher numeric tuple (e.g. 0.1.20). This ADR's first ship is v0.1.20-beta1, which bumps the tuple and is therefore reachable.

**Trigger to revisit**
- If we ever automate `versionName` from the latest git tag at build time (a small Gradle plugin), this rule becomes enforced by tooling rather than by discipline. Worth doing if we forget the bump even once.

## Alternatives considered

- **Keep `versionName` as the bare numeric form, change the comparator** so it treats `pre` as "later than stable when versionCode > current". Possible but introduces a separate ordering for the in-app vs. for human readers; SemVer is doing the right thing here, our data was wrong.
- **Embed the tag name verbatim in `BuildConfig`** via a Gradle step that reads the latest tag during configuration. Adds Gradle complexity for a once-per-release human decision; rejected for now.
- **Only set the suffix for `-beta1` and trust monotonic `versionCode` for the rest.** Brittle; relies on every comparator everywhere checking versionCode.

## References

- `app/build.gradle.kts` — the `versionName` line.
- `app/src/main/kotlin/.../data/update/UpdateRepository.kt` — `SemVer` comparator.
- ADR-0005 — pre-release tag convention.
- ADR-0008 — in-app updater that surfaced the bug.
