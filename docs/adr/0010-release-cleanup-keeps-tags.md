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

## Amendment 2026-05-18 — release-entry retention policy

The original decision covers *tags* (always kept). This amendment makes
the durable convention for which **GitHub Release entries** are kept
explicit, so trimming isn't re-litigated each time the `/releases` page
gets cluttered. Status stays **Accepted**.

The retention policy is:

1. **All git tags are always kept** — the original ADR-0010 rule,
   restated. Never `--cleanup-tag`; compare-links must keep resolving.
2. **Every stable `vX.Y.Z` Release entry is kept** — this is the
   canonical, installable version history; it is never pruned.
3. **Only the single newest pre-release entry is kept, and only while
   it is ahead of the latest stable** — it is the live testing pointer.
   Older `-betaN` / `-rcN` entries of completed cycles are superseded.
   Once a cycle is promoted to a stable `vX.Y.Z`, ALL of that cycle's
   (and older) pre-release entries are pruned: the stable supersedes
   even the newest beta, since there is no testing pointer ahead of the
   stable left to retain.
4. **Superseded beta/rc Release *entries* of completed cycles are
   pruned** (`gh release delete <tag> --yes`). Their git tags remain
   (rule 1); their attached APK / mapping assets go with the entry —
   acceptable, CI build artifacts expire on their own retention anyway,
   and the stable promotion of that cycle carries the shipped APK.

The prune itself is a **separate maintainer-run housekeeping step**,
never automated and never deletes tags. This amendment records the
convention only; performing it is out of band.

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

## Amendment 2026-11-10 — tags are now mechanically protected

"Never delete a tag" relied on discipline. The repository ruleset "Protect release tags" now applies to `refs/tags/v*` and blocks **update** (moving a tag) and **deletion**; creation stays allowed. Consequences: the re-push recovery above (delete/recreate a tag, steps 1–3) now needs the ruleset disabled temporarily — a deliberate break-glass step, as intended. A wrong tag is fixed by a new commit and a new tag (ADR-0015), never by moving it. The release-entry pruning policy in the previous amendment is unaffected (it deletes Release entries, not tags). Retention is also not yet being applied: the `v0.1.40-beta1…beta10` entries are still published; that is a process item, tracked separately.

## Amendment 2026-11-13 — immutable releases

Once the repository setting "immutable releases" is on (issue #295, WP2), a published release locks its assets and its tag. The tag of an immutable release cannot be deleted while the release exists, and after the release is deleted its tag name can never be reused. The recovery recipe above (re-push a deleted tag) therefore does not apply to those tags; it was already moot because the tag ruleset blocks deletion with no bypass. The setting applies only to releases published after it is switched on.

Pruning is unaffected: the retention policy deletes old beta/rc release *entries* and keeps their tags, which stays allowed (`gh release delete <tag> --yes`, never `--cleanup-tag`). The release body, title and the pre-release and latest flags stay editable, but nothing in the flow edits or uploads to a release after publication: `release.yml` publishes draft-first in a single `gh release create` (ADR-0014 amendment), so the assets are in place before the release is public.

A re-run (`gh run rerun`) uses the `release.yml` of the tagged commit. A release run started before WP2 therefore still runs the old file with `softprops/action-gh-release`, which publishes a pre-release first and uploads the assets afterwards. Once immutable releases are on, never re-run a release run whose tagged commit predates WP2: it would publish a beta without its APK and lock the tag. Only runs of tags made after WP2 are re-runnable.
