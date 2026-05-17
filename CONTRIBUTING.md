# Contributing to UnraidControl

Thanks for wanting to contribute. The workflow is intentionally lightweight.

## Branching model

- **`main`** is always shippable. Direct pushes are blocked; everything lands via PR.
- **Feature branches**: name them whatever (`feature/x`, `fix/y`, `bump-foo`). Branch off `main`, push to the same repo if you're a collaborator, or push to your fork.

## Workflow

1. Branch from `main`:
   ```bash
   git checkout main && git pull
   git checkout -b fix/loading-spinner
   ```
2. Make changes, commit, push.
3. Open a Pull Request against `main`.
4. CI (`ci.yml`) runs automatically on the PR. It builds debug + signed release APKs in one shot (≈ 4 min on a cold cache). Wait for green.
5. Merge when CI is green. Squash-merge is the default if there are many small commits; merge-commit is fine for cohesive ones.

You may merge your own PR after CI passes — no required reviews. Reviews are welcome but not blocking, so the project doesn't stall when only one person is around. If you want a review, ping in the PR thread.

## Cutting a release

Tags are the release trigger. After your code is on `main` AND `ci.yml` is green for that commit:

```bash
git checkout main && git pull
git tag -a v0.2.0 -m "v0.2.0 — what's new"
git push origin v0.2.0
```

`release.yml` then runs. It does **not** rebuild — it downloads the release APK that `ci.yml` already produced for this commit's SHA, and attaches it to a new GitHub Release.

If you tag too early (before CI is green), the release workflow fails with a clear message; just wait for CI and push the tag again.

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
| Stable promotion | `release: v0.2.0 (stable) — <one-line summary>` |

The `<summary>` is what the cut delivers (the bundled feature/fix), not
"bumped versionCode" — the diff already shows the bump.

### Pre-releases (beta → rc → stable)

```bash
git tag -a v0.2.0-beta1 -m "v0.2.0 beta 1"
git push origin v0.2.0-beta1
```

Trailing digit is required (`v0.2.0-beta` without a number is rejected). See **[ADR-0005](docs/adr/0005-prerelease-tag-convention.md)** for the convention; **[ADR-0006](docs/adr/0006-pragmatic-beta-first-release-policy.md)** for when beta-first is required.

### Cheat-sheet — what triggers what

| If the PR touches… | Action |
|---|---|
| Compose UI / single-file bug fix / docs | Direct stable allowed (ADR-0006). |
| Docs only (`*.md`, `LICENSE`, `docs/`, AI-assistant configs) | **No version bump, no tag** — CI skips the APK build automatically (ADR-0009). |
| `schema.graphqls`, GraphQL ops, `GraphQlMapper.kt`, Apollo config | Beta-first required (ADR-0006). |
| `AndroidManifest.xml` (new permissions/components) | Beta-first required (ADR-0006). |
| `SettingsStore.kt` keys / DataStore migration | Beta-first required (ADR-0006). |
| `app/build.gradle.kts` plugin/dependency bumps | Beta-first required (ADR-0006). |
| New end-to-end feature touching install + UI flow | Beta-first required (ADR-0006). |
| Anything affecting how the app talks to the Unraid server | Beta-first required (ADR-0006). |

When in doubt: beta-first. The in-app updater (ADR-0008) means betas reach the dev's device with no extra friction.

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

Signed release builds locally (only useful if you have the keystore + passwords):

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
- The `https://claude.ai/code/session_…` line stays as an
  informational trailer at the very end.

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
- Models are in `data/model/Models.kt` and are the contract between UI and the GraphQL layer. Renaming GraphQL fields should only touch `schema.graphqls`, `queries.graphql`, `mutations.graphql`, and `data/api/GraphQlMapper.kt`.

## Project layout

See `README.md` for the full architecture overview. Quick orientation:

- `app/src/main/graphql/` — Apollo schema + operations
- `app/src/main/kotlin/.../data/` — repositories, Apollo client, domain models
- `app/src/main/kotlin/.../ui/` — Compose screens + components
- `.github/workflows/` — CI/CD

## License

This project is licensed under the GNU General Public License v3.0 (see [`LICENSE`](./LICENSE), relicensed from CC BY-NC-SA 4.0 — see [ADR-0021](./docs/adr/0021-relicense-to-gpl-3.md)). By submitting a contribution you agree your changes are released under the same license.
