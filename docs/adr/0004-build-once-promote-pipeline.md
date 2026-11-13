# ADR-0004: Build-once-promote pipeline

- **Status**: Accepted
- **Date**: 2026-05-13
- **Tags**: ci, release

## Context

A naive release pipeline rebuilds the APK when a release tag is pushed. That's slow (~4 min cold cache), wasteful (the same source already passed CI minutes earlier), and dangerous: the source state can differ subtly between the CI run that validated the commit and the release run that ships an APK from that same commit (different dependency resolution, different cache state, different runner image). Every rebuild is a new chance for a regression to sneak in.

We want a release to be **a promotion of a known-good artifact**, not a new build.

## Decision

Split CI into two workflows:

- **`ci.yml`** — runs on every PR and push to `main`. Builds debug + signed-release APKs, uploads them as artifacts (`app-debug`, `app-release`, `mapping`).
- **`release.yml`** — runs on `v*` tag push. **Does not rebuild.** It:
  1. Resolves tag → commit SHA.
  2. Finds the latest successful `ci.yml` run for that SHA via the GitHub API.
  3. Downloads the `app-release` and `mapping` artifacts from that run.
  4. Renames the APK to `UnraidControl-${TAG}.apk`.
  5. Detects pre-release flag (see ADR-0005).
  6. Creates the GitHub Release with `generate_release_notes: true`.

Total release-workflow runtime: ~16 seconds.

## Consequences

**Positive**
- The APK that ships is bit-for-bit the one CI validated. No drift.
- Release tagging is ~30× faster.
- A release can only happen if the commit's CI was green — `release.yml` fails with a clear "no successful CI run found for commit" message otherwise.

**Trade-offs**
- The release workflow now depends on artifact retention (90 days for `app-release`). Retention shorter than the gap between code-on-main and tag-on-that-commit would break releases. Currently fine; we tag within hours.
- If CI for a commit was cancelled (e.g. by `cancel-in-progress`) and not rerun, the tag will fail — see ADR-0011 for the structural fix to that adjacent issue.

**Trigger to revisit**
- If artifact retention proves expensive or we want longer-lived "release-able" commits, switch to a build-on-tag fallback that uses the same signed-keystore inputs.

## Amendment 2026-11-12 — release-key signing is limited to human-authored pull requests and manual dispatch

The `build-release` job used to sign with the keystore from the Actions secrets on every same-repo pull request. GitHub withholds secrets from Dependabot pull requests, but not from pull requests that other bots open from same-repo branches (the Renovate app, installed for the organization). A dependency update authored by a bot would then run third-party build code next to the release key. The earlier rule (`github.actor != 'dependabot[bot]'`) only decided whether an empty secret was an error; it did not keep the secret away from anyone.

Decision:

- Signing runs in its own job, `build-release`, whose job-level `if` admits only `workflow_dispatch` and same-repo pull requests whose **author** (`github.event.pull_request.user`) is neither of type `Bot` nor a login ending in `[bot]`. The author is used, not `github.actor`: a human re-run or "update branch" on a bot pull request changes the actor but not the author. The decision is made on the `github` context before any runner starts, so no step can change it (a step-level `env` gate could be flipped through `$GITHUB_ENV`), and for every other run no runner receives the signing secrets at all.
- Bot-authored and fork pull requests run `build-release-unsigned` instead, the exact negation of that condition. It references no secret and compiles the minified release variant of both flavors, which the fallback in `app/build.gradle.kts` signs with the debug key, so a green bot pull request still proves the R8 build.
- The aggregator `build` requires exactly one of the two release jobs to succeed and the other to be skipped.
- Promotion is unchanged: `release.yml` only ever promotes the `app-release` artifact of a run on the tagged commit, and bot-authored pull requests never produce one.

Residual: a human-authored pull request that pulls in bot-written changes is still signed; whoever opens the pull request is responsible for its content.

## Amendment 2026-11-13 — sign at promotion, in a protected environment (issue #295, WP3)

The key was available to every human same-repo pull request build, which runs the Gradle plugins, annotation processors and build-time code of every dependency. A platform policy can keep it away from those builds, but only one that matches the ref of the run: a `pull_request` run has `refs/pull/N/merge`, so an environment limited to `v*` tags refuses every pull request run. Moving the secrets therefore moves the signing step, from the pull request to the tag. This replaces the 2026-11-12 amendment above, whose author-based `if` kept the secrets from bot pull requests only.

Decision:

- **ci.yml builds the release APK once, with no secret**, for every run (human, bot, fork, dispatch). `app/release.keystore` does not exist, so `app/build.gradle.kts` signs with the debug key (unchanged; the F-Droid build of the `store` flavor, which builds from source without our key, sees the same input as before). The jobs `build-release-unsigned` and the author condition are gone. The artifacts are still `app-release` (the `direct` APK) and `mapping`.
- **release.yml signs that artifact at the tag**, in the job `sign` (between `guard` and `publish`), which declares `environment: release` and `contents: read`. The environment holds `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` and its deployment policy accepts only tags matching `v*`; GitHub refuses the job on any other ref before it starts. The job decodes the keystore into the runner's temp directory, runs `apksigner sign` (`.github/scripts/release-sign.sh`: v2 on; v1, v3, v4 off, like every published APK so far), checks `zipalign -c -P 16 -v 4`, compares the entry list with CRC-32 values of the downloaded and the signed APK (signature files excluded; any difference fails), runs `release-verify-apk.sh` unchanged (pinned certificate, exactly one signer, so a surviving debug signature fails closed), deletes the keystore, and hands the signed APK to `publish` as an artifact of the same run. `publish` uses that APK and the mapping from the CI run.
- **"Bit-for-bit" becomes "entry for entry".** What is promoted is the same compiled code and resources that the pull request built and tested, entry for entry (the comparison above proves it on every release); the signature is added at promotion. The released file is not byte-identical to the CI artifact, and nothing needs it to be: the in-app updater checks the published asset's digest, and immutable releases freeze that digest.
- **Costs.** Pull request CI no longer proves that the release key works; a broken environment secret fails the tag run before anything is published, and is fixed by correcting the secret and re-running the run (`gh run rerun`), with no new tag. A release takes about a minute longer. A bug in the sign job uses up that version (fix forward, ADR-0015).
- **Residual.** Who may push a `v*` tag stays a convention (R-RELEASE); the environment has no required reviewer, because every tag is the maintainer's decision and all guards run before signing.

## Alternatives considered

- **Build on tag push** (the naive approach) — slower and introduces drift.
- **Build only on `main` push, never on PR** — would skip CI on the PR, defeating the gate (ADR-0003).
- **Cross-build (debug + release in parallel jobs)** — adds complexity without saving wall-time since release is the longer task anyway.

## References

- `.github/workflows/ci.yml` — the build half.
- `.github/workflows/release.yml` — the promotion half.
- ADR-0003 — PR-gate that backs the "always green CI before tag" invariant.
- ADR-0009 — docs-only CI bypass interacts with this (docs commits don't produce a release artifact; tagging them fails fast, which is correct).
