# ADR-0010: Release cleanup keeps tags (never `--cleanup-tag`)

- **Status**: Accepted (release-notes content amended by [ADR-0031](./0031-curated-changelog.md); the tag-keeping + compare-link rationale here is unchanged)
- **Date**: 2026-05-14
- **Tags**: release, process

## Context

`release.yml` uses `generate_release_notes: true` (ADR-0004). GitHub bakes a block into each release body that ends with:

```
**Full Changelog**: https://github.com/.../compare/vPREV...vCURR
```

The URL is **resolved at view time** — meaning it's checked against the current state of refs every time someone loads the release page, not when the release was created.

We periodically trim the releases list (UI clutter on the `/releases` page). The natural-looking command is `gh release delete <tag> --cleanup-tag --yes`, which also deletes the underlying git tag. We did that once: it nuked the predecessor tags for every release we kept, so every "Full Changelog" link on the still-published releases pointed at a deleted ref and 404'd.

## Decision

When trimming releases, use:

```bash
gh release delete <tag> --yes
```

**Never** `--cleanup-tag`. The Releases UI gets trimmed (which is the goal); the git tag stays (a tiny `refs/tags/vX.Y.Z` entry in the repo); the compare-link in every newer release keeps working.

**Recovery recipe** (when a tag is deleted by mistake and the commit is still on `main`):

1. `gh workflow disable Release` — otherwise the tag push triggers `release.yml`, which fails on the missing CI artifact and leaves a noisy failed run.
2. `git push origin <tag>` from the local ref (or recreate it from the right commit SHA first).
3. `gh workflow enable Release`.

After step 2 the compare-link rendering on existing releases recovers automatically.

## Consequences

**Positive**
- Compare-links stay valid forever, even as old releases are trimmed.
- Tags cost essentially nothing (one ref entry + zero bytes if pointing at an existing commit).

**Trade-offs**
- `git tag -l` and `git ls-remote --tags` show every tag we've ever published. Cosmetic; users browsing the website see only the un-trimmed releases.

**Trigger to revisit**
- Never, realistically. The cost of keeping tags is trivial and the failure mode of deleting them is loud (broken links on the most-visible page of the project).

## Alternatives considered

- **Use `--cleanup-tag` and edit the surviving release bodies to remove dead compare-links.** Manual, fragile, and we'd have to redo it every time we trim. Loses information (the compare link is useful when it works).
- **Stop using `generate_release_notes: true`** and write a custom changelog that doesn't include a compare-link. Loses the useful "What's Changed" auto-section; the real fix is keeping tags, not removing the feature that relies on them.
- **Switch to a release-notes generator that bakes the diff inline (no URL).** Would solve the symptom, but the URL is genuinely useful when alive — we want it to keep working.

## References

- `.github/workflows/release.yml` — `generate_release_notes: true` line.
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) — cleanup recipe documented practically.
- ADR-0004 — release pipeline that introduces the auto-changelog.
