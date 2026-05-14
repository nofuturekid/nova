# ADR-0005: Pre-release tag convention `-beta1` / `-rc1` (trailing digit required)

- **Status**: Accepted
- **Date**: 2026-05-13
- **Tags**: release

## Context

Pre-release tags need a convention that's:
- Easy to type and read at a glance.
- Sortable by tooling (semantic ordering of betas, rcs, stable).
- Robust against typos that would silently mark a beta as stable.

Common conventions: `v1.0.0-beta`, `v1.0.0-beta.1`, `v1.0.0-beta1`, `v1.0.0.beta1`. None is universally right; the choice is what we want to live with.

## Decision

Pre-release tags follow the regex `v[0-9]+\.[0-9]+\.[0-9]+-(alpha|beta|rc|pre)[0-9]+` — **the trailing digit is required**.

Examples: `v0.1.18-beta1`, `v0.1.18-beta2`, `v0.1.18-rc1`, `v0.1.18-rc2`, `v0.1.18` (stable).

`release.yml` checks the tag against this regex (`-(alpha|beta|rc|pre)[0-9]+$`) and:
- If matches → sets the GitHub Release as `prerelease: true` (gets the "Pre-release" badge, hidden from "Latest release").
- If not → stable, "Latest release".

A tag like `v0.1.18-beta` (no digit) is **rejected** as a pre-release — it would otherwise be tagged stable, which would mis-publish a beta.

## Consequences

**Positive**
- Typo-resistant: forgetting the `1` immediately fails the pre-release detection, which is loud (release shows up as "Latest", clearly wrong) — but only after we caught it via the regex enforcement. We've never accidentally shipped a beta as stable.
- Numbered suffixes give natural ordering: `-beta1 < -beta2 < -rc1 < -rc2 < (stable)` matches dev intuition.
- The in-app updater's semver parser handles this exact form (see `UpdateRepository.kt`).

**Trade-offs**
- Strict regex means `v0.1.18-beta.1` (semver-standard) is also rejected. We chose `-beta1` over the dot form for brevity and shell-pasting ergonomics. Diverges from strict SemVer.
- "First beta" must be `-beta1`, not just `-beta`. Minor friction at tag time.

**Trigger to revisit**
- If we ever publish to a package registry that requires strict SemVer (e.g. npm, Maven Central), switch to `-beta.1` form.

## Alternatives considered

- **Strict SemVer with dotted suffix (`-beta.1`)** — more "correct" but the dot is a usability tax in shells and feels foreign in git tag names.
- **No digit (`-beta`, `-rc`)** — looks cleaner but enables the typo failure mode we're avoiding.
- **Calendar-versioned betas (`-beta-20260513`)** — useful when there are many betas a day; overkill for our pace.

## References

- `.github/workflows/release.yml` — `Detect pre-release` step.
- `app/src/main/kotlin/.../data/update/UpdateRepository.kt` — semver parser must stay aligned with this format.
- ADR-0006 — release policy that defines *when* betas are required.
