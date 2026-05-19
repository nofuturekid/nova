# ADR-0036: Run unit tests in CI (blocking); basic Gradle cache provider; CodeQL v4 + default-branch push

- **Status**: Accepted
- **Date**: 2026-05-18
- **Tags**: ci, security, testing

## Context

The 2026-05-18 code-review triage (recorded in PR #145) surfaced three
CI-governance gaps, none of them app behaviour:

1. **CI never executes the unit tests.** The repo carries real JVM unit
   tests (`app/src/test/...`: `UpdateControllerTest`,
   `StaleCrossServerTest`) plus the `junit` + `kotlinx-coroutines-test`
   test dependencies, but `ci.yml` only ran `assembleDebug`/`lintDebug`
   and `assembleRelease`. The Gradle test task was never invoked, so the
   test backlog tracked as #18–22 cannot catch regressions: a broken
   assertion would still merge green. The required `build` gate proved
   nothing about behaviour.
2. **A proprietary enhanced-caching provider auto-enabled.**
   `gradle/actions/setup-gradle@v6` defaults its cache provider to
   `gradle-actions-caching` (a GitHub-hosted, proprietary enhanced cache).
   For this public repo both the GitHub Actions cache and the enhanced
   cache are free (DISTRIBUTION.md), so this is not a cost issue — it is
   FOSS / supply-chain hygiene: we should not silently route build inputs
   through an opt-out proprietary service when the stock `actions/cache`
   backend is sufficient.
3. **CodeQL deprecations + a default-branch Security-tab gap.**
   `codeql.yml` pinned `github/codeql-action/*@v3`, which runs on Node 20
   and emits the v3-deprecation + Node20-deprecation warnings (v4 supports
   Node 24). Separately, CodeQL warns that an `on.push` hook is needed to
   "analyze and see code scanning alerts from the default branch on the
   Security tab": after PR #130 deliberately moved the heavy `java-kotlin`
   scan off every PR to a weekly schedule/dispatch (cost), there is no
   analysis run on `main` itself, so the Security tab's default-branch
   baseline is never populated.

Constraints: the required merge gate is the single `build` status check
(ADR-0003); branch protection is Tier-2 shared infra that agents must not
mutate (ADR-0027). New blocking checks must therefore gate *through* the
existing `build` aggregator, not via a new required check. PR #130's
PR-vs-scheduled CodeQL cost split, and PR #123's deliberate
`build-mode: manual` for `java-kotlin`, must both be preserved.

## Decision

Adopt five CI-governance changes; no app code, version bump, tag, or
release (CI/infra + ADR only).

1. **Run unit tests in CI, blocking.** Add a `test` job to `ci.yml` that
   runs `./gradlew :app:testDebugUnitTest`, gated by the same
   `setup.outputs.code == 'true'` skip filter (ADR-0029) and built with
   the same setup/cache structure as `build-debug` (checkout, JDK 21
   temurin, `setup-gradle`, Android SDK). It runs in parallel with
   `build-debug`/`build-release` (the ADR-0019 pattern). The `build`
   aggregator now `needs:` `test` and fails the gate unless
   `test.result == success`. Because `build` is the *existing* required
   status check, a failing test blocks merge **without any
   branch-protection change** (ADR-0027 Tier-2 honoured; ADR-0003
   single-check contract preserved).
2. **`cache-provider: basic`.** Add `with: cache-provider: basic` to every
   `gradle/actions/setup-gradle@v6` step (the two in `ci.yml`,
   `build-debug` + `build-release`, the new `test` job inherits it, and
   the one in `codeql.yml`'s `java-kotlin` build), preserving each step's
   existing `cache-read-only` key. This opts out of the proprietary
   enhanced cache and uses the stock `actions/cache` backend.
   (`dependency-submission.yml` uses `gradle/actions/dependency-submission`,
   a *different* action with no such input — out of scope, untouched.)
3. **CodeQL action v3 → v4.** In `codeql.yml`, bump
   `github/codeql-action/init` and `github/codeql-action/analyze` from
   `@v3` to `@v4` (no `autobuild` step exists). Resolves the Node 20 +
   v3-deprecation warnings.
4. **CodeQL default-branch push trigger (option a).** Add
   `on: push: branches: [main]` with the *same* `paths-ignore` as
   `pull_request`, and extend the `config` job so a `push` event is
   treated exactly like `pull_request` — it resolves to the fast
   `actions`-only language list (build-mode `none`, no Gradle/Android
   build). This populates the default-branch Security-tab baseline while
   **preserving PR #130's cost design**: the heavy `java-kotlin` compile
   stays on the weekly `schedule` + `workflow_dispatch` only and is *not*
   added to per-push runs.
5. **`build-mode: manual` — no code change.** PR #123 set
   `java-kotlin`'s `build-mode: manual` because `build-mode: none` /
   autobuild produced an empty DB (exit 32) on this Android project. It is
   retained intentionally. The "overlay-base unavailable, full DB"
   message is informational only; the overlay-base optimization is
   knowingly forgone — acceptable for an advisory (non-required) scanner.

## Consequences

**Positive**
- The required `build` gate now verifies behaviour, not just that the app
  compiles: a regression caught by `UpdateControllerTest` /
  `StaleCrossServerTest` (or future #18–22 tests) blocks merge.
- Build inputs no longer flow through the opt-out proprietary enhanced
  cache — clearer FOSS / supply-chain posture, stock `actions/cache`.
- No more CodeQL v3 / Node 20 deprecation warnings (v4 → Node 24).
- The Security tab now shows default-branch code-scanning alerts (the
  fast `actions` baseline on every `main` push), closing the visibility
  gap without re-introducing a per-push heavy scan.
- Zero branch-protection / repo-setting change (ADR-0027 Tier-2 honoured);
  the single-required-check contract (ADR-0003) is unchanged.

**Negative / trade-offs**
- The `test` job adds one parallel job per build-affecting PR (its wall
  time overlaps `build-debug`/`build-release`, so end-to-end CI grows only
  if tests are the long pole).
- `cache-provider: basic` may yield slightly lower cache hit quality than
  the enhanced provider; accepted for the hygiene benefit on a small
  single-module build.
- `push:main` adds one fast `actions`-only CodeQL run per merge to main —
  intentional and cheap (no compile), the minimum needed for the Security
  tab. The default-branch `java-kotlin` findings still lag up to a week
  (weekly schedule), an accepted advisory-scanner trade-off.
- **Known/expected consequence:** GitHub code-scanning's "1 configuration
  not found / cannot determine PR-introduced alerts for `java-kotlin`"
  warning on PRs is EXPECTED and intentional — PRs run the `actions`-only
  config by design (PR #130 cost decision, preserved by decision 4), so
  no per-PR `java-kotlin` baseline exists to diff against. Do **not**
  "fix" this by adding `java-kotlin` to per-PR runs; the Security-tab
  default-branch baseline is covered by `on.push: main` (decision 4) plus
  the weekly `java-kotlin` schedule run (decision 4 / PR #130).

**Trigger to revisit**
- The `test` job materially slows CI (becomes the wall-time long pole or
  flakes) → split slow/fast suites or run a subset on PRs.
- Gradle changes the semantics/availability of the `basic` cache provider
  → re-evaluate the provider choice.
- CodeQL v4 changes `build-mode` behaviour such that `none`/autobuild
  becomes viable for this Android project → drop the manual compile
  (revisits decision 5 / PR #123).
- The weekly `java-kotlin` cadence proves insufficient for the Security
  tab (a real default-branch finding lands too late) → reconsider moving
  `java-kotlin` onto a (still bounded) push trigger, weighing PR #130's
  cost rationale.

## Known consequence — transient plugin-fetch in the `test` job (2026-05-19)

The new blocking `test` job (decision 1) runs on PRs under
`cache-read-only` + `cache-provider: basic` (decision 2). Unlike a
warm/writable cache, it must **fetch Gradle plugins fresh** during the
configuration phase, so it is susceptible to a plugin-portal / registry
blip *before any test executes*.

Observed 2026-05-19: PR #163 run-2's `test` job failed in the Gradle
**configuration phase** —
`Plugin [id: 'com.google.devtools.ksp', version: '2.3.8'] … could not
resolve 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.8'`
from Google / MavenCentral / GradlePluginPortal. The **same-commit**
`build-debug` / `build-release` jobs passed (identical `pluginManagement`
repos + `setup-gradle@v6` config — verified), and #163 run-1 was fully
green including `test`. This is therefore **not a code/test defect and
not a CI-config defect**: it is a transient registry fetch failure in the
fresh-fetch `test` job.

It is also **not caused by, and not fixed by, PR #164**
(`InstallStatusReceiver.resetForTest` test-isolation hardening). #164 is
a *latent* test-isolation hardening retained on its own merit (maintainer
B decision); it is categorically unrelated to a configuration-phase KSP
plugin-fetch failure. PR #164's merged commit message overstates
causation (it was dispatched on a blind log-403 flake hypothesis);
history is not rewritten — **this note is the correction of record**:
#164 stays as a deliberate latent hardening, NOT the #163 fix.

**Mitigation:** re-run the failed `test` job — the failure is transient.

**Trigger to revisit / escalate:** if this recurs frequently, harden the
`test` job (Gradle dependency-resolution retry, or warm its
plugin/dependency cache so it does not fetch fresh on every PR). Tracked
as a follow-up; **not done here** (this is the documentation correction
only).

## Alternatives considered

- **Keep the enhanced cache provider (do nothing for #2).** It is free on
  this public repo, so the only cost is the supply-chain/FOSS posture.
  Rejected: opting into a proprietary, opt-out service for build inputs
  when the stock backend suffices is exactly the hygiene this review
  flagged; `basic` is one line and reversible.
- **Keep tests unrun (status quo) / run them non-blocking.** A
  non-blocking test job would catch nothing — a red test that still
  merges is no better than no test. The whole point of the backlog #18–22
  is regression *prevention*, which requires the gate. Wiring through the
  existing `build` aggregator makes it blocking with no infra change, so
  there is no reason to settle for advisory.
- **Leave CodeQL weekly-only (no `push:main`).** Preserves #130's cost
  design perfectly but leaves the Security tab's default-branch baseline
  permanently empty — the exact warning we are resolving. The chosen
  approach (push → `actions`-only) gets the baseline for near-zero cost
  while keeping the heavy `java-kotlin` scan weekly, so it dominates
  weekly-only.
- **`push:main` runs the full `actions` + `java-kotlin` scan.** Simplest
  config (no `config`-job change) but re-introduces the per-push heavy
  compile PR #130 deliberately removed for cost. Rejected for that exact
  reason.

## References

- ADR-0003 — the single required `build` status check; the contract this
  wires the test gate through (no branch-protection change).
- ADR-0011 — CI concurrency / cancel-in-progress shape the jobs live in.
- ADR-0019 — parallel debug/release jobs; the pattern the new `test` job
  follows.
- ADR-0022 — CodeQL is advisory / least-privilege; why the scanner stays
  non-required and `build-mode: manual` is acceptable.
- ADR-0027 — agent autonomy & access model; Tier-2 forbids
  branch-protection / repo-setting changes (the hard guardrail honoured
  here).
- ADR-0029 — the build-affecting allowlist; the `test` job reuses its
  `setup.outputs.code` skip gate.
- PR #130 — PR-vs-scheduled CodeQL scope split (java-kotlin moved to
  weekly/dispatch for cost) — preserved by decision 4.
- PR #123 — `build-mode: manual` for `java-kotlin` (autobuild failed) —
  retained by decision 5.
- PR #145 — the 2026-05-18 code-review triage record that surfaced these
  three gaps and the test backlog #18–22.
- `.github/workflows/ci.yml` — the new `test` job + aggregator wiring +
  `cache-provider: basic`.
- `.github/workflows/codeql.yml` — v4 bump, `push:main` trigger, `config`
  scope update, `cache-provider: basic`.
</content>
</invoke>
