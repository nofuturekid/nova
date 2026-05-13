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

### Pre-releases (beta / rc)

Tags with `-alpha`, `-beta`, `-rc`, or `-pre` in their name get the GitHub "Pre-release" flag automatically. They don't appear as "Latest release" on the repo page.

```bash
git tag -a v0.2.0-beta.1 -m "v0.2.0 beta 1"
git push origin v0.2.0-beta.1
```

This is the test channel — sideload these for early validation without pushing them on every user.

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
