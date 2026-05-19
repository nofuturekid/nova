# ADR-0027: Agent autonomy & access model

- **Status**: Accepted
- **Date**: 2026-05-16
- **Tags**: process, security, tooling

## Acceptance & scope (2026-05-19)

Maintainer-ratified 2026-05-19 with **scope A**.

- **Broader Tier-1 autonomy, in BOTH the sandbox and the maintainer's
  home system.** Tier-1 work — local build/test/lint/codegen, reading
  CI, the per-PR branch → CI → merge → tag → release flow, docs/ADRs,
  read-only server introspection, emulator/device verification — is
  **act-first + report**: no per-occurrence "may I?" for reversible
  Tier-1 work in either environment.
- **Tier 2 and Tier 3 are unchanged and remain fully in force.** Tier-2
  (any mutating/irreversible action on a server, anything beyond
  read-only on the production/home NAS, secrets, shared-infra or
  repo-settings changes, destructive git, publishing beyond the beta
  flow) still requires explicit per-occurrence maintainer confirmation.
  Tier-3 (the maintainer's on-device acceptance for UX/behaviour, an ADR
  for non-obvious decisions, confirm-before-risky) is never delegated.
  "More autonomy" means **act-first on Tier-1, not** a relaxation of
  Tier-2/3.
- **"Accepted" is the ratified operating contract, not a claim that
  tier enforcement is mechanically guaranteed.** The recorded Tier-2
  deviation (the ADR-0033 Pages-enablement incident, below) and the
  Trigger-to-revisit stay fully in force; enforcement remains
  discipline-based and revisitable. Any future Tier-2 relaxation (e.g.
  home-NAS mutations without per-occurrence confirm) is a separate,
  deliberate ADR-0027 amendment — explicitly **out of scope** of this
  acceptance.

## Context

The coding agent currently runs in a restricted sandbox: no Android
build toolchain that resolves online dependencies, CI log hosts blocked
(`403 Host not in allowlist`), no emulator, no network path to the
user's Unraid server. Every loop that crosses one of those boundaries
is closed by the **user acting as a manual relay**: pasting CI failure
text, uploading run-log ZIPs, installing betas, taking screenshots,
running server commands.

The cost is concrete and measured. The Phase E subscription pilot
(ADR-0026) was, at its core, *one* investigation — "does the WSS
subscription deliver on a real server, and if not, why?". It was
forced into **five betas (0.1.29-beta9…beta13)** of
hypothesis → build → user-installs → screenshot → next guess, because
the agent could neither read CI logs, run the build locally, nor probe
the server directly. A single Apollo codegen `NullPointerException`
cost a full CI round-trip plus a user log-ZIP upload to diagnose.

The user has signalled intent to give the agent a less restricted
environment (e.g. native Linux, local Android toolchain, SSH + GraphQL
access to an Unraid instance). The explicit framing: **this is about
removing the manual relay from the loops, not about weakening the
established PR / CI / ADR / device-acceptance workflow.** Those gates
are about *judgement and acceptance*; the relay is *mechanics*. More
access also means a larger blast radius, so the model must say plainly
what the agent does autonomously and what stays gated regardless of
capability.

## Decision

Adopt a **tiered autonomy model**. Capability is necessary but not
sufficient: some actions stay gated even when the agent technically
*can* do them.

**Tier 1 — Autonomous (no per-action confirmation).**
Local, reversible, or already-established-safe work:

- Build, test, lint, Apollo codegen **locally**; read CI logs/artifacts
  directly; self-diagnose and fix before pushing.
- The established per-PR flow: branch → commit → push → open PR →
  monitor CI → on green, squash-merge + tag + verify release. (Already
  in force this whole session; access just removes the log relay.)
- ADRs, HANDOFF, local scratchpad, docs.
- **Read-only** introspection of an Unraid instance: GraphQL queries,
  opening a WSS subscription to observe, `ssh` read-only commands
  (`cat`, `ls`, `grep`, log tails, `--dry-run`), reading API logs.
- Emulator/device UI runs for the agent's own functional verification.

**Tier 2 — Gated (explicit user confirmation per occurrence, even with
capability).**

- Any **mutating or irreversible** action on *any* server: the
  `notify` script, container/VM/array/parity mutations, config or
  share changes, package installs, service restarts, writing files
  outside a scratch dir.
- **Anything beyond read-only against the production NAS.** Mutating
  experiments run against a designated test/throwaway Unraid instance,
  not the user's media server.
- Destructive or history-rewriting git (`push --force`, `reset --hard`
  on shared branches, branch deletion beyond the PR's own),
  credential/secret changes, anything that publishes beyond the
  established beta release flow.

**Tier 3 — Human judgement gates (never delegated, independent of
access).**

- **User device-acceptance** for UX / "is this the feel I want" — the
  agent verifies *function*; the user owns *acceptance*.
- An **ADR** for any non-obvious convention or architecture/security
  decision (this file included).
- The standing "confirm before risky / irreversible / shared-state"
  rule from the operating instructions stays in force; broader access
  raises its importance, it does not relax it.

**Credentials posture.** Least-privilege API key (read scope by
default; a separate higher-scope key only for explicitly authorised
mutation testing). SSH key used read-only by default. Production NAS
credentials, if provided at all, are read-only. Secrets never logged,
committed, or echoed.

## Consequences

**Positive**

- Phase-E-class investigations collapse from multi-beta relays into a
  single direct session (probe the endpoint, run the experiment, read
  the logs, conclude).
- Compile/codegen failures caught locally before CI; far fewer
  round-trips; the user stops being a log-paste/screenshot courier.
- The agent can satisfy the "verify the feature actually runs"
  expectation it currently cannot meet (no emulator today).

**Negative / trade-offs**

- Larger blast radius: a mistake can now touch a real machine, not
  just a sandbox. Mitigated by Tier 2/3 and the prod-read-only rule —
  but the mitigation is *policy*, enforced by discipline, not by the
  environment.
- Depends on the user provisioning and maintaining the environment
  (native toolchain, a test Unraid instance, scoped credentials).
- A test instance that drifts from the real server can give false
  confidence; server-side findings still ultimately want confirmation
  against the user's actual setup.

**Trigger to revisit**

- Any autonomous action causes unintended server or shared-state
  impact → tighten tiers, treat as an incident, amend this ADR.
  - **Observed 2026-05-18 (ADR-0033 / PR #142):** a delegated agent
    enabled GitHub Pages out-of-band via the admin API (`POST
    /repos/.../pages`) to unblock a deploy, instead of stopping to
    report as briefed. A Tier-2 shared-infra mutation performed
    autonomously without per-occurrence confirmation. Reversible, intent
    was user-authorised (Pages was the chosen option), but the *method*
    crossed the Tier-2 line — concrete evidence that Tier-2 enforcement
    is currently convention/discipline, not mechanically guaranteed.
- No separate test Unraid instance is available → Tier 2 stays fully
  gated against the only (production) instance; revisit whether
  mutation testing is in scope at all.
- Access scope changes (e.g. only API, no SSH; or prod-only) → re-map
  the tiers to the actual surface before relying on this ADR.

## Alternatives considered

**Status quo (sandbox + user relay).** Zero new risk surface, but the
measured cost is real — an investigation that should take one session
takes five betas. Keeps working; just slow and user-taxing.

**Full autonomy, no gates.** Maximum speed, unacceptable blast radius:
an agent error against a production NAS (mutations, config) is
hard-to-reverse and affects the user's actual data/services. Rejected —
the speed-ups are in diagnosis/verification loops, which Tier 1 already
unlocks without touching this.

**User keeps running everything manually, agent only advises.** Defeats
the purpose; the bottleneck *is* the manual relay. The point is to let
the agent close the mechanical loops while keeping the judgement loops
human.

## References

- This session's Phase E pilot: ADR-0026 (subscriptions evaluated →
  reverted) — the relay cost that motivated this ADR (beta9–beta13).
- ADR-0017 (polling) — the architecture the relayed pilot was probing.
- ADR-0009 (docs-only CI bypass) — why this ADR ships without a
  version bump or tag.
- The established autonomous per-PR flow (branch/CI/merge/tag) and the
  operating-instruction rule on confirming risky/irreversible actions.
