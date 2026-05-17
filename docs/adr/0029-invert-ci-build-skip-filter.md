# ADR-0029: Invert the CI build-skip filter to a build-affecting allowlist

- **Status**: Proposed
- **Date**: 2026-05-17
- **Tags**: ci, process
- **Supersedes**: [ADR-0009](./0009-docs-only-ci-bypass.md) (docs-only CI bypass via deny-list)

## Context

ADR-0009 added a fast path: `ci.yml`'s `setup` job diffs the change and,
if every changed file matches a **deny-list of non-shipping paths**
(`*.md`, `LICENSE`, `docs/`, `.editorconfig`, AI-assistant config dirs,
…), skips the heavy Android build while still reporting `SUCCESS` on the
required `build` aggregator. Anything *not* on that list forces a full
build.

That list is an *allowlist of things safe to skip*. Its failure mode is
safe (an unrecognised path builds) but its maintenance cost is real and
unbounded: **every new non-shipping directory must be added or it wastes
a full ~3–4 min build forever.** This bit us immediately — PR #101 added
`docker/`, `scripts/`, and `.dockerignore` (a local-CI harness that
cannot affect the APK or the pipeline) and ran a full build purely
because those paths weren't in the deny-list. The non-shipping surface
(docs, tooling, editor/AI config, container files) grows continuously;
the deny-list chases it indefinitely.

ADR-0009's own *trigger to revisit* names the exit: *"If the whitelist
grows past ~15 entries it's becoming hard to scan — consider an inverted
'code-only' allowlist instead."* This ADR actions that trigger.

## Decision

**Invert the filter: enumerate the small, stable set of build-affecting /
shipping paths. If a changed file matches, do a full build; otherwise
skip.** Replace the `grep -v <deny-list>` with `grep -E <code-allowlist>`
in `ci.yml`'s detect step (renamed *Detect build-affecting change*).

Build-affecting allowlist (anchored regex):

```
^app/                  app source, res, manifest, app/build.gradle.kts, proguard
^gradle/               version catalog (libs.versions.toml) + wrapper
^gradlew$ ^gradlew\.bat$  wrapper launchers
^settings\.gradle\.kts$   module graph
^build\.gradle\.kts$      root build script
^gradle\.properties$      build flags (KSP, caching, jvmargs, …)
^\.github/workflows/      the CI/release pipeline shape itself
```

The early fail-safes are unchanged: `workflow_dispatch`, no diff base,
force-push, and new-branch all still force a full build.

Two safeguards make allow-by-default acceptable *for this repo*:

1. **`release.yml` is a hard backstop.** A tagged commit with no
   successful CI build fails the release with *"no successful CI run
   found for commit"* (ADR-0004) — a mis-skipped code change can never
   silently ship.
2. **Same-PR convention.** Introducing a new buildable module/path
   **MUST** add it to this allowlist in the *same* PR — the identical
   discipline ADR-0003 already mandates for renaming the `build` job vs.
   branch protection.

This is sound here specifically because the project is a **single-module
app with a very stable build topology**: the build-affecting set is tiny
and rarely changes, while the non-shipping set grows constantly. We now
maintain the small, stable list instead of the large, growing one.

## Consequences

**Positive**
- New non-shipping paths (tooling, `docker/`, `scripts/`, future config)
  get the ~10 s fast path automatically — no per-directory deny-list
  upkeep. PR #101-class changes would now skip the build.
- The maintained list is small, stable, and semantically meaningful
  ("these paths affect the artifact"), not an ever-growing catalogue of
  things to ignore.
- `.github/dependabot.yml` and similar non-build config now also skip
  (previously forced a full build) — a free correctness win.

**Negative / trade-offs**
- The failure mode flips from *safe* (unknown → build) to *efficient*
  (unknown → skip). A genuinely new code-bearing path absent from the
  allowlist would be skipped. Mitigated, not eliminated, by the
  `release.yml` backstop and the same-PR convention — this is **policy
  enforced by discipline + a release-time net, not a build-time
  mechanism.** Acceptable for a solo / single-module project; would be
  riskier for a multi-module repo with many contributors.
- The convention is a human rule. If someone adds `core/` without
  updating the allowlist, that PR's CI skips — caught at the latest by
  the release backstop, but ideally in review.

**Trigger to revisit**
- The app becomes multi-module, or routine direct-pushes return: the
  unknown→skip risk rises; reconsider a hybrid (allowlist for code +
  an "unrecognised top-level path → build" guard) or revert to the
  deny-list.
- A real incident where a code change was wrongly skipped and only the
  `release.yml` backstop caught it → tighten to the hybrid guard above
  and treat as an incident.

## Alternatives considered

- **Keep ADR-0009's deny-list, just add `docker/`, `scripts/`,
  `.dockerignore`.** The incremental fix. Rejected: it postpones the
  same problem to the next new tooling directory; the *wrong* (growing)
  list keeps being maintained. ADR-0009's own revisit trigger points
  away from this.
- **Pure allow-by-default with no convention or backstop reasoning.**
  Rejected: leaves the new-module risk completely unmanaged. The
  `release.yml` net + same-PR rule are what make the inversion
  defensible here.
- **Hybrid now (code allowlist + unknown-top-level-path → build
  guard).** Safest, but reintroduces "maintain a recognised set" and
  added complexity for a risk the `release.yml` backstop already covers
  at this scale. Named explicitly as the *trigger-to-revisit* target if
  the project grows, rather than paying its complexity prematurely.
- **Schema/dependency-graph-driven detection** (ask Gradle what inputs
  matter). Correct in principle, heavy in practice; far more machinery
  than a single-module app's stable path set warrants.

## References

- Supersedes [ADR-0009](./0009-docs-only-ci-bypass.md) — actions its
  "inverted code-only allowlist" revisit trigger.
- ADR-0003 — required `build` check; precedent for the "update X in the
  same PR" convention this relies on.
- ADR-0004 — build-once-promote; `release.yml`'s "no successful CI run
  found" failure is the backstop that makes allow-by-default safe.
- ADR-0011 / ADR-0014 — concurrency / single-CI-run shape the detect
  step lives in.
- PR #101 (`docker/` + `scripts/` local-CI harness) — the concrete
  full-build-for-nothing case that motivated this.
- `.github/workflows/ci.yml` — the `Detect build-affecting change` step.
