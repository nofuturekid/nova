# Contributing to NOVA

Thanks for wanting to contribute. The workflow is intentionally lightweight.

## Branching model

- **`main`** is always shippable. Everything lands via PR; the only push to `main` is the maintainer's fast-forward push of a green PR head (below).
- **Feature branches**: name them whatever (`feature/x`, `fix/y`, `bump-foo`). Branch off `main`, push to the same repo if you're a collaborator, or push to your fork.

## Workflow

1. Branch from `main`:
   ```bash
   git checkout main && git pull
   git checkout -b fix/loading-spinner
   ```
2. Make changes, commit, push.
3. Open a Pull Request against `main`.
4. CI (`ci.yml`) runs automatically on the PR. It builds debug + release APKs in one shot (the release APK is signed with the debug key; no job of a pull request run has the release signing key, which is only used at the tag) (≈ 8 min for a code PR; docs-only PRs finish in seconds). Every run, docs-only included, also lints the workflows (actionlint), the scripts in `.github/scripts/` (shellcheck) and `renovate.json` (when it changed), rejects session links and trailers in commit messages, and runs the release preflight when `versionName` changes (shape, `versionCode`, free tag name, non-empty `CHANGELOG.md` section). All of it is part of the required check `build`. Wait for green.
5. Maintainers land a green PR by a fast-forward push of its head to `main` (no merge button; squash, merge commits and Update branch are disabled). Commits must be signed; `main` is linear. If `main` moved while CI ran, rebase the branch onto `origin/main` (the commits stay signed) and wait for the new CI run; do not use the Update-branch button, it creates a merge commit that `main` refuses.

### Claude Code setup (maintainer tooling)

The `.claude/` directory configures how the maintainer works with Claude Code: the "Working rules" (rule IDs such as R-FREEZE) in `.claude/CLAUDE.md`, two role files (`nova-builder`, `nova-reviewer`), three saved workflows in `.claude/workflows/` (`nova-research`, `nova-pr`, `nova-merge-train`), the `release-engineer` agent and two skills. External contributors can ignore all of it.

Files there are instructions that run with the maintainer's credentials, so changes under `.claude/` are reviewed like code, and a saved workflow is never run from a checkout of someone else's PR branch. A commit made with Claude ends with `Co-Authored-By: Claude <Model> <noreply@anthropic.com>`, naming the model that wrote the change; no session link is added (R-TRAILER).

## Contribution flow (external contributors)

External contributors submit PRs from a fork. CI on a fork PR proves
**compile/lint only** — fork PRs get no secrets by design, so signed
release/device steps don't run there. The maintainer then reviews, runs
the beta and the on-device acceptance pass (ADR-0027 Tier 3 — a human
judgement gate, not delegable), and lands/releases. So an appearance or
UX change can't be self-accepted in a fork PR; flag it and the
maintainer owns acceptance.

Commits pushed to `main` must be signed, and a fork contributor's commits usually are not. The maintainer then re-signs them through a rebase (authorship is kept), lands them through a same-repository PR, and credits the contribution in `CHANGELOG.md`. Because that PR builds with the release secrets, the maintainer reviews the diff first.

There are no required reviews, so the project doesn't stall when only one person is around; the maintainer lands a PR once CI is green. Reviews are welcome but not blocking. If you want a review, ping in the PR thread.

## Cutting a release

Tags are the release trigger. After your code is on `main` AND `ci.yml` is green for that commit (push `main` first, check that it holds the commit, and only then push the tag, so the release guard finds the commit on `main`):

```bash
git checkout main && git pull
gh workflow run release.yml -f sha=$(git rev-parse HEAD) -f version=0.2.0   # dry run: only the guard job runs
# wait for it: the guard job must be green and `publish` skipped (HANDOFF.md, "Release dry run")
git tag -a v0.2.0 -m "v0.2.0 — what's new"
git push origin v0.2.0
```

`release.yml` then runs. It does **not** rebuild — it downloads the release APK and `mapping.txt` that `ci.yml` already produced for this commit (the PR head SHA) and publishes them as a new GitHub Release. The release is created as a draft, both files are uploaded, and only then it becomes public, in one `gh release create` command (this is what lets immutable releases work).

If you tag too early (before CI is green), the release workflow fails with a clear message; just wait for CI and re-run the failed release run (`gh run rerun <run-id>`). Tags cannot be re-pushed (the ruleset forbids update and deletion).

Before anything is downloaded or published, the read-only `guard` job checks that the tag matches what CI built: the commit is on `main`, it was the head of its own pull request and that pull request's `ci.yml` run was green on exactly this commit (a `workflow_dispatch` run made after tagging does not count; for tags before 2026-11-13 the squash commit's PR must have been merged as this commit), the CI run built the same tree, `versionName` in `app/build.gradle.kts` equals the tag (including a `-betaN` suffix), `versionCode` is higher than at the previous tag, and `CHANGELOG.md` has a non-empty section for the version (`.github/scripts/release-preflight.sh`, the same check the pull request runs). Then the job `sign` (the only one that has the release key, as secrets of the GitHub environment `release`, which accepts only `v*` tags) replaces the debug signature of the CI-built APK with the release signature, proves with an entry-by-entry CRC-32 comparison that nothing else changed, and runs the pinned-signer check; if it fails because of a wrong environment secret, correct the secret and re-run the run (`gh run rerun <run-id>`). Only the `publish` job has write access, and it runs only after the guard and the signing passed. A failure names the mismatch; fix it with a new commit, not by moving the tag (tags are protected against update and deletion). If the artifact lookup finds no CI run (e.g. after a direct push), (or the artifact expired) run `gh workflow run ci.yml --ref <tag>` (it reports as `build (dispatch)` and never as `build`, so it cannot satisfy the ruleset), wait for it, then re-run the failed release run (`gh run rerun <run-id>`). With tags locked, a guard failure on a version mismatch uses up that tag: fix forward with a new commit and the next version. Before pushing the tag, run the dry run, which executes only the guard (`gh workflow run release.yml -f sha=<release PR head> -f version=<versionName>`; see HANDOFF.md, "Release dry run"). After signing (and again in `publish`), a second step reads the APK itself (`aapt2`/`apksigner`) and requires package `io.github.nofuturekid.nova`, `versionName` == tag, `versionCode` == the value in source, and the pinned release-signing certificate before anything is published ([ADR-0014](docs/adr/0014-single-ci-run-per-pr.md), `.github/scripts/release-verify-apk.sh`).

### Naming the release commit / PR

Per [ADR-0015](docs/adr/0015-promotion-requires-new-commit.md) every tag
has its own version-bump commit (and PR). **Name that commit and PR
`Release …`, not `Version bump …` / `Promote …`.** The word "Release"
makes the project history scannable at a glance — `git log --oneline`
and the PR list immediately show where each shipped artifact came from.

These use the `release:` Conventional-Commits type (see **Commit
messages** below) so they're both CC-compliant *and* greppable:

| Cut | Commit / PR title |
|---|---|
| Beta / rc | `release: v0.2.0-beta1 — <one-line summary>` |
| Stable | `release: 0.2.0 — <one-line summary> (Stable)` |

The `<summary>` is what the cut delivers (the bundled feature/fix), not
"bumped versionCode" — the diff already shows the bump.

### Pre-releases (beta → rc → stable)

```bash
gh workflow run release.yml -f sha=$(git rev-parse HEAD) -f version=0.2.0-beta1   # dry run first: guard green, `publish` skipped
git tag -a v0.2.0-beta1 -m "v0.2.0 beta 1"
git push origin v0.2.0-beta1
```

Trailing digit is required (`v0.2.0-beta` without a number is rejected by the release guard). See **[ADR-0005](docs/adr/0005-prerelease-tag-convention.md)** for the convention; **[ADR-0006](docs/adr/0006-pragmatic-beta-first-release-policy.md)** for when beta-first is required.

### Cheat-sheet — what triggers what

| If the PR touches… | Action |
|---|---|
| Compose UI / single-file bug fix / docs | Direct stable allowed (ADR-0006). |
| Docs only (`*.md`, `LICENSE`, `docs/`, AI-assistant configs) | **No version bump, no tag** — CI skips the APK build automatically (ADR-0029). |
| `schema.graphqls`, GraphQL ops, `GraphQlMapper.kt`, Apollo config | Beta-first required (ADR-0006). |
| `AndroidManifest.xml` (new permissions/components) | Beta-first required (ADR-0006). |
| `SettingsStore.kt` keys / DataStore migration | Beta-first required (ADR-0006). |
| `app/build.gradle.kts` plugin/dependency bumps | Beta-first required (ADR-0006). |
| New end-to-end feature touching install + UI flow | Beta-first required (ADR-0006). |
| Anything affecting how the app talks to the Unraid server | Beta-first required (ADR-0006). |

When in doubt: beta-first. The update notice (ADR-0046) announces each beta to the dev's device and sends the browser to the APK, so betas reach it with little friction.

### Cleaning up old releases

```bash
gh release delete <tag> --yes      # never --cleanup-tag
```

Keeps the git tag so the auto-generated `Full Changelog` compare-links on newer releases keep resolving. See **[ADR-0010](docs/adr/0010-release-cleanup-keeps-tags.md)** (incl. recovery recipe for accidentally-deleted tags).

## Architecture decisions

Major decisions live as ADRs under [`docs/adr/`](docs/adr/). Read those first if you're wondering *why* something is the way it is; this file describes *how* to work within the conventions.

## Local builds

### Pre-push: run CI locally (recommended)

Before pushing, run the exact CI checks in a pinned container — "green
locally ⇒ green in CI", zero toolchain on the host:

```bash
./scripts/local-ci.sh
```

See [`docs/local-build.md`](docs/local-build.md) for the cache model and
design. CI round-trips are expensive here; this catches
compile/lint/codegen failures before they reach CI.

### Host builds

Daily debug builds (no keystore needed):

```bash
./gradlew :app:assembleDebug
```

Signed release builds locally (only useful if you have the keystore + passwords; CI no longer builds a release-signed APK, the release run signs at the tag):

```bash
export KEYSTORE_PASSWORD='...'
export KEY_PASSWORD='...'
export KEY_ALIAS='unraidcontrol'
# put release.keystore at app/release.keystore
./gradlew :app:assembleRelease
```

Otherwise the release build falls back to debug-signing locally.

## Commit messages

Follow **[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)**.

```
<type>(<optional scope>): <imperative summary, ≤72 chars, no trailing period>

<body — wrap ~72 cols; explain WHY, not how; bullets fine>

<optional footer / trailers>
```

**Types used in this repo:**

| Type | For |
|---|---|
| `feat` | user-facing feature |
| `fix` | bug fix |
| `refactor` | behaviour-preserving restructure |
| `perf` | performance |
| `docs` | docs only (README, ADRs, this file) |
| `ci` | `.github/workflows/*`, build pipeline |
| `build` | Gradle, dependencies, plugin/AGP/Kotlin bumps |
| `chore` | misc housekeeping, no src/behaviour change |
| `revert` | reverts a prior commit |
| `release` | the ADR-0015 version-bump commit (see *Naming the release commit / PR*) |

**Scopes** (optional, lowercase, pick the touched area): `docker`,
`vms`, `array`, `overview`, `settings`, `container`, `update`, `data`,
`theme`, `adr`, `deps`. Omit if it spans many.

**Rules** (the classic seven + CC specifics):
- Imperative mood: `add`, not `added`/`adds`.
- Summary capitalised after the `:`, no full stop, ≤72 chars.
- Blank line before the body.
- Breaking change: `!` before the colon (`feat(data)!: …`) **and**
  a `BREAKING CHANGE:` footer explaining the migration.

**Examples**

```
feat(array): add parity-check start/pause/resume/cancel
fix(docker): clear updating pill when snapshot reports UpToDate
refactor(data): split snapshot poll into per-domain streams
ci: parallel debug + release jobs with cache warming
docs: relicense CC BY-NC-SA → GPL v3 (ADR-0021)
release: v0.1.27-beta1 — VM reboot/reset + parity control
```

Bad: `Fixed bug`, `updates`, `WIP`, `misc changes`, `Docker stuff.`

## Code style

- Kotlin: idiomatic. No formatter enforced yet — basic IntelliJ defaults are fine.
- Compose: `@Composable` functions are PascalCase. Helpers and small private composables can live in the same file as their sole caller.
- Models are in `data/model/Models.kt` and are the contract between UI and the GraphQL layer. Renaming GraphQL fields should only touch `schema.graphqls`, `queries.graphql`, `mutations.graphql`, `subscriptions.graphql`, and `data/api/GraphQlMapper.kt`.

## Project layout

See [`HANDOFF.md`](HANDOFF.md) ("Layout") and [`docs/adr/`](docs/adr/) for the architecture overview. Quick orientation:

- `app/src/main/graphql/` — Apollo schema + operations
- `app/src/main/kotlin/.../data/` — repositories, Apollo client, domain models
- `app/src/main/kotlin/.../ui/` — Compose screens + components
- `.github/workflows/` — CI/CD

## License

This project is licensed under the GNU General Public License v3.0 (see [`LICENSE`](./LICENSE), relicensed from CC BY-NC-SA 4.0 — see [ADR-0021](./docs/adr/0021-relicense-to-gpl-3.md)). By submitting a contribution you agree your changes are released under the same license.
