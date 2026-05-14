# ADR-0006: Pragmatic risk-categorised beta-first release policy

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: release, process

## Context

In v0.1.11 and v0.1.13 we tagged "stable" releases that built green in CI, passed lint, and then crashed against the live Unraid server because the GraphQL schema had drifted. Twice in a row. The CI gate (ADR-0003) caught compile/test errors but couldn't catch live-API drift; the only signal was a real device against a real server.

Tagging everything beta-first would catch this, but it adds friction to every release including tiny UI tweaks where the risk is zero. We need a policy that's strict where it matters and loose where it doesn't.

## Decision

**Pragmatic risk-categorised** release policy.

**Direct stable (`vX.Y.Z`) allowed when the PR only touches:**

- Compose UI (visuals, copy, layout)
- Documentation (`*.md`, `LICENSE`, `docs/`)
- `.github/workflows/*` (doesn't ship to users)
- A single-file bug fix the dev has already verified on their device

**Beta-first required when the PR touches any of:**

- `schema.graphqls`, `*.graphql` operations — schema drift was the original sin.
- `GraphQlMapper.kt` or Apollo scalar config
- `AndroidManifest.xml` — permissions, components
- `SettingsStore.kt` keys or DataStore migration
- `app/build.gradle.kts` plugin / dependency bumps
- New end-to-end features touching install + UI flow
- Anything affecting how the app talks to the Unraid server

**Flow for risky changes**: merge to `main` → tag `-beta1` → test on device → bug → fix-PR → tag `-beta2` → promote same commit to `-rc1` (optional) → promote same commit to `vX.Y.Z` stable.

**When in doubt: beta-first.** The in-app updater (ADR-0008) means betas reach the dev's own device with no extra friction.

## Consequences

**Positive**
- Cheap changes (UI tweaks, docs) ship immediately. High velocity for low risk.
- High-risk changes get a real-device check before "Latest release" appears for everyone.
- The list is concrete enough that the dev (and AI assistants) don't have to re-decide every PR.

**Trade-offs**
- The list is judgement-based, not algorithmic. Edge cases ("is changing a Coil dependency 'high risk'?") need human read.
- Two-stage promotion (beta → stable) means two tags per release in the risky-change case. More tags, more cleanup discipline (ADR-0010).

**Trigger to revisit**
- If we ship a stable bug after following the policy correctly, the categorisation missed something — add it to the beta-first list.
- If the list grows to 15+ items, the categorisation is too fine-grained; revisit the rule shape.

## Alternatives considered

- **Always-beta-first.** Catches everything but adds tag-and-wait friction to a 4-line copy fix. Optimises for the wrong cost.
- **Always-direct-stable.** What we did before; broke twice.
- **Time-based (24h beta soak before stable).** Adds wall-clock delay without proportional benefit; the issues we hit were caught in minutes on device, not 24h.

## References

- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) — practical "how to tag" steps reference this ADR.
- ADR-0005 — tag convention used by the beta-first flow.
- ADR-0008 — in-app updater that delivers betas to the dev's device.
