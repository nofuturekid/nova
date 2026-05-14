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

### Pre-releases (beta → rc → stable)

Tags following the convention `vX.Y.Z-beta1`, `-beta2`, …, `-rc1`, `-rc2`, … (also `-alpha1`, `-pre1`) get the GitHub "Pre-release" flag automatically. They don't appear as "Latest release" on the repo page. The in-app updater treats them as updates only when "Include pre-releases" is enabled in Settings.

```bash
git tag -a v0.2.0-beta1 -m "v0.2.0 beta 1"
git push origin v0.2.0-beta1
```

The trailing number is required — `v0.2.0-beta` without a digit is rejected (catches typos).

### Release policy

Two failed "stable" releases in a row taught us: not every change is safe to ship to "Latest" without a sniff test. We now follow a **pragmatic risk-categorised** rule.

**Direct stable (`vX.Y.Z`) is allowed when the PR only touches:**

- Compose UI (visuals, copy, layout)
- README / CONTRIBUTING / HANDOFF / other docs
- `.github/workflows/*` (doesn't ship to users)
- A single-file bug fix the dev has already verified on their device

**Beta-first is required when the PR touches any of:**

- `schema.graphqls`, `*.graphql` operations — schema drift bugs (v0.1.11, v0.1.13)
- `GraphQlMapper.kt` or Apollo scalar config
- `AndroidManifest.xml` — new permissions / components
- `SettingsStore.kt` keys or DataStore migration
- `app/build.gradle.kts` plugin / dependency bumps
- New end-to-end features touching the install + UI flow
- Anything affecting how the app talks to the Unraid server

**Flow for risky changes:**

1. Merge PR to `main` as normal.
2. Tag `vX.Y.Z-beta1`. Release workflow publishes as pre-release. Test on a device.
3. Bug found → fix-PR → tag `-beta2`, repeat.
4. Stable on a beta → promote to `vX.Y.Z-rc1` (same commit, different tag). Last quick sanity check.
5. Quiet on the RC → tag the same commit as `vX.Y.Z` stable.

In practice for solo dev: usually `beta1` → user tests on the device with "Include pre-releases" turned on → if good, jump straight to stable (skip the rc step) for minor changes; use rc only when the gap between the previous stable and this one feels material (lots of changes, schema work, etc.).

When in doubt: beta-first. The in-app updater means betas reach the dev's own device with no extra friction.

**Docs-only PRs need no version bump and produce no release.** Pure documentation and assistant-config changes merge to `main` without bumping `versionCode` / `versionName` and without a `v*` tag — they don't ship to users. CI detects them automatically and skips the APK build (~10 seconds instead of ~4 minutes), still reporting `SUCCESS` on the `build` check so branch protection is happy. The whitelist covers:

- `*.md` anywhere (README, CONTRIBUTING, HANDOFF, `CLAUDE.md`, `AGENTS.md`, `GEMINI.md`, `.github/copilot-instructions.md`, …)
- `LICENSE`, `docs/`, `.gitattributes`, `.editorconfig`
- AI assistant config: `.claude/`, `.cursor/`, `.cursorrules`, `.continue/`, `.aider*`, `.windsurfrules`, `.github/instructions/` (Cursor, Claude Code, Continue, Aider, Windsurf, Copilot)

If the PR also changes anything else (Kotlin, Gradle, workflows, manifest, resources), treat it as a code change and bump the version normally.

## Local builds

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

This project is licensed under CC BY-NC-SA 4.0 (see `LICENSE`). By submitting a contribution you agree your changes are released under the same license.
