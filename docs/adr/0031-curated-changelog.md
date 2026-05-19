# ADR-0031: Curated CHANGELOG.md drives release notes

- **Status**: Accepted
- **Date**: 2026-05-17
- **Tags**: release, process, docs
- **Amends**: [ADR-0010](./0010-release-cleanup-keeps-tags.md) (release-notes
  content; ADR-0010's tag-keeping + compare-link rationale is unchanged)

> Status reconciled to Accepted 2026-05-18 — the decision has been in force
> since every beta/stable release ships a curated `CHANGELOG.md` section that
> drives the in-app updater notes (e.g. [0.1.31], [0.1.32-beta1]). ADR was
> merged as Proposed by a process slip.

## Context

`release.yml` publishes with `generate_release_notes: true` only, so a
release body is just GitHub's auto "What's Changed" — a list of merged
**PR titles** + a compare link. The in-app updater shows that body
verbatim. A conventional-commit PR title ("fix(overview): dark-mode
bottom-nav icons invisible after M3 migration") is not a user-readable
changelog: it doesn't state the symptom a user saw, and there is no
grouped "Features / Fixed" view. The maintainer asked for plain-text
bug reports and bulleted Added/Fixed in the changelog.

ADR-0010's reason for `generate_release_notes: true` (the auto compare
link, and never deleting tags so it keeps resolving) is still valid and
must be preserved — this ADR changes *what else* the body contains, not
that.

## Decision

Adopt a committed **`CHANGELOG.md`** in [Keep a Changelog] style as the
single source of user-facing release notes.

- Sections `## [version] - date` with `### Added` / `### Changed` /
  `### Fixed` bullets in plain language (user symptom, not PR title).
- `## [Unreleased]` accumulates entries as PRs merge. **Contributor
  rule:** any PR changing user-facing behaviour adds a bullet under
  `[Unreleased]`. The ADR-0015 version-bump PR renames `[Unreleased]`
  to the version + date and opens a fresh `[Unreleased]`.
- `release.yml` gains a step that slices the section whose version
  matches the pushed tag (tag `vX` → header `## [X]`) into a file and
  passes it as `body_path` to `softprops/action-gh-release`.
  **`generate_release_notes: true` stays** — the action appends the auto
  "What's Changed" + compare link *below* the curated section, so
  ADR-0010's compare-link property is retained and the in-app updater
  leads with human prose.
- Extraction is **non-fatal**: a missing/empty section emits a
  `::warning::` and the release still publishes with just the auto
  notes. A missing changelog entry must never block a release.

## Consequences

**Positive**
- Release bodies (and the in-app changelog) lead with grouped,
  plain-language Added/Fixed — what the maintainer asked for.
- Single in-repo source, reviewed in the same PR as the change;
  `git log` of `CHANGELOG.md` is the history. Closes the long-standing
  HANDOFF "CHANGELOG.md from tags" TODO.
- ADR-0010's auto compare link + tag-keeping unchanged (auto notes still
  appended).

**Negative / trade-offs**
- Contributor discipline: an `[Unreleased]` bullet per user-facing PR.
  Not machine-enforced (a CI check could be added later; deliberately
  not now — keep it lightweight, mirrors ADR-0029's "convention not
  mechanism" stance). Non-fatal extraction means a forgotten entry
  degrades gracefully rather than failing the ship.
- Merge conflicts in `[Unreleased]` across parallel PRs — low for a
  solo/low-contributor project; standard Keep-a-Changelog cost.
- `release.yml` is touched → that PR runs a full build (ADR-0029).

**Trigger to revisit**
- Contributors routinely forget entries → add a soft PR check that
  warns when user-facing paths changed but `CHANGELOG.md` didn't.
- If `generate_release_notes` ever has to be turned off, re-evaluate
  the ADR-0010 compare-link dependency explicitly.

## Alternatives considered

- **Auto notes + a curated `## Highlights` block prepended.** Lighter,
  but changelog content lives in two places (release body vs nowhere
  in-repo); no reviewable history. Rejected — the maintainer wants a
  real changelog, and in-repo single-source is the durable form.
- **Per-release manual `gh release edit`.** Zero infra but unenforced
  and easy to forget; not a source of truth. Used once for
  v0.1.30-beta3 as a stopgap before this ADR; not the steady state.
- **Drop `generate_release_notes`, CHANGELOG only.** Loses ADR-0010's
  auto compare link. Rejected — keep both, curated first.

## References

- Amends [ADR-0010](./0010-release-cleanup-keeps-tags.md) (auto notes /
  compare link — preserved, now appended below the curated section).
- [ADR-0015](./0015-promotion-requires-new-commit.md) — the version-bump
  PR is where `[Unreleased]` is rolled to a version.
- [ADR-0029](./0029-invert-ci-build-skip-filter.md) — why the
  `release.yml` change makes this PR a full build; "convention not
  mechanism" precedent for the un-enforced contributor rule.
- `CHANGELOG.md`, `.github/workflows/release.yml`.
- [Keep a Changelog]: https://keepachangelog.com/en/1.1.0/
