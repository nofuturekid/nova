# ADR-0025: Dependabot as a low-noise advisory radar (no auto-merge)

- **Status**: Accepted
- **Date**: 2026-05-16
- **Tags**: ci, dependencies, security

## Context

Dependency and GitHub-Actions-pin maintenance has been fully manual.
The AGP-9 cycle (ADR-0023) showed the cost: resolving correct target
versions required out-of-band research (Claude-chat prompts, GitHub
API), and the action pins were bumped by hand (PR-2). It also showed
the risk: Apollo 4→5, multiplatform-markdown-renderer 0.27→0.40 and the
AGP built-in-Kotlin DSL were real API breaks. CI only proves
compile/lint — every dependency bump still needs the project's
beta-cycle + on-device verification (ADR-0006/0013/0015).

So the gap is "what changed, when, and the correct target version", not
"apply it blindly". A fully autonomous updater (auto-merge, weekly PR
swarm) would fight the deliberate beta/ADR release flow and burn the
(non-trivial) CI on every PR.

## Decision

Adopt **Dependabot as a deliberately low-noise, non-autonomous radar**
via `.github/dependabot.yml`:

- **`github-actions`** ecosystem: monthly, single grouped PR, no
  auto-merge. Highest-value / lowest-risk (these were the hand-bumped
  pins).
- **`gradle`** ecosystem (root + `app/build.gradle.kts` + the
  `libs.versions.toml` version catalog): monthly, single grouped
  "radar" PR, no auto-merge. Treated as a *suggestion* — it still goes
  through the normal beta cycle + device verification; a grouped PR may
  be split when a major (Apollo-5-class) bump rides along.
- `open-pull-requests-limit: 3`, Conventional-Commit prefixes
  (`ci`/`build`) to match the repo's commit style.
- **Dependabot security alerts + automated security fixes** enabled as
  a repo setting (not expressible in `dependabot.yml`): low-noise,
  high-value, fires only on real advisories.
- **No auto-merge anywhere.** Bumps are reviewed and shipped through
  the beta gate like any other change.

This is infra+docs only — no app code, no `versionCode`/beta bump
(consistent with how ADR-0018/0022 CI/infra changes were handled).

## Consequences

**Positive**
- Removes the manual "is there a newer version, which one" legwork —
  Dependabot opens PRs with the correct target version pre-resolved.
- Keeps the GitHub-Actions pins current automatically (the tedious
  ADR-0023-PR-2 work, now ongoing for free).
- Security advisories surface proactively for a published app.
- Monthly + grouped + no-auto-merge keeps it compatible with the
  beta/ADR flow instead of fighting it.

**Negative / trade-offs**
- Each Dependabot PR triggers the (non-trivial) CI.
- A grouped Gradle PR can mix a risky major with trivial minors →
  reviewer must split it when needed; the group is a radar, not a
  rubber stamp.
- Verification burden is unchanged — Dependabot does not replace the
  on-device beta check.

**Trigger to revisit**
- PR noise still too high → move Gradle to quarterly or split groups by
  update-type (major separate).
- A future, lower-risk subset could get auto-merge (e.g.
  github-actions patch) if the beta flow ever tolerates it.

## Alternatives considered

- **Stay fully manual.** Rejected: the AGP-9 cycle showed the
  research/pin legwork is real and recurring.
- **Dependabot with auto-merge.** Rejected: contradicts the beta gate +
  on-device verification (ADR-0006/0013/0015); CI can't prove
  UI/runtime.
- **Weekly / per-dependency PRs.** Rejected: PR swarm vs. the
  deliberate release flow; monthly grouped is the minimum-noise form.
- **Renovate.** More powerful/configurable, but Dependabot is native,
  zero-infra, and sufficient for a radar; not worth the extra surface.

## References

- ADR-0023 (manual toolchain/pin bump that motivated this; the API
  breaks that mandate verification).
- ADR-0018 / ADR-0022 (CI/infra changes treated as infra, no app bump —
  precedent for this one).
- ADR-0006 / ADR-0013 / ADR-0015 (beta-first + version-bump policy that
  dependency bumps continue to follow).
- ADR-0020 (KSP2; example of a dependency move that needed real
  verification, not a blind bump).
