# ADR-0009: Docs-only CI bypass via path diff + step gating

- **Status**: Superseded by [ADR-0029](./0029-invert-ci-build-skip-filter.md)
- **Date**: 2026-05-14
- **Tags**: ci

## Context

The CI gate (ADR-0003) runs on every push and every PR. With Gradle + Android SDK + Compose + signed-release-APK + lint, the cold-cache run is ~4 minutes. On warm cache it's ~2. Multiply by the rate of doc-only PRs (CONTRIBUTING tweaks, HANDOFF updates, this very ADR) and a significant fraction of CI minutes is spent building the same APK that nobody will ever install.

We also kept reflexively bumping `versionCode` / `versionName` for doc-only changes "because the workflow expects it" — even though no APK was actually shipping.

GitHub's `paths-ignore` trigger would skip the workflow entirely, but: the `build` job is a **required status check** for branch protection. A skipped workflow doesn't satisfy a required check; the PR stays unmergeable. Classic GitHub gotcha.

## Decision

**Detect-and-gate inside the job**, not at the workflow trigger level.

`ci.yml` starts with a `Detect docs-only change` step that:

1. Resolves the diff base/head pair based on event type (`pull_request` → base.sha / head.sha; `push` → before / sha; `workflow_dispatch` → full build).
2. Falls back to a full build if no diff base is available (force-push, new branch).
3. `git diff --name-only base head` and applies a whitelist regex:
   - `*.md` anywhere (covers README, CLAUDE.md, AGENTS.md, GEMINI.md, ADRs, …)
   - `LICENSE`, `docs/`, `.gitattributes`, `.editorconfig`
   - AI-assistant config dirs: `.claude/`, `.cursor/`, `.continue/`, `.aider*`, `.windsurfrules`, `.cursorrules`, `.github/instructions/`
4. Sets `outputs.code = 'true' | 'false'`.

Every heavy step (JDK setup, Gradle, Android SDK, keystore decode, assemble, lint, signing, artifact uploads) is gated by `if: steps.filter.outputs.code == 'true'`.

The job still **runs end-to-end and reports `SUCCESS` on the `build` check** — but for docs-only changes it finishes in ~10 seconds (checkout + path filter).

We also document the consequence in CONTRIBUTING/HANDOFF: docs-only PRs **do not bump the version** and **are not tagged**.

## Consequences

**Positive**
- Docs PRs cycle through CI in seconds. We use the bypass dozens of times per session in practice.
- Branch protection stays satisfied; no exceptions or admin bypass needed.
- The whitelist explicitly covers AI-assistant config files — recognising that we (the dev + LLMs) are part of the working set.
- Falls back to full build whenever in doubt (no diff base, manual dispatch). Safe default.

**Trade-offs**
- The whitelist is a strict allowlist — new docs file types (e.g. someone adds a `.txt` README, or `.rst` docs) won't trigger the bypass until the regex is updated. Acceptable; better than the opposite failure mode (a code change accidentally bypassing the build).
- If branch protection ever renames the required check from `build` to something else, the bypass mechanism must keep the same job name — it relies on the SUCCESS conclusion under the same check name.

**Trigger to revisit**
- If we add a heavier always-on step (e.g. integration tests against a real server) that should also be gated, restructure the gating block.
- If the whitelist grows past ~15 entries it's becoming hard to scan — consider an inverted "code-only" allowlist instead.

## Alternatives considered

- **`paths-ignore` on workflow trigger** — required check stays "expected", PR unmergeable. The reason we chose detect-and-gate instead.
- **Companion "docs CI" workflow with the same `build` check name** — GitHub does support same-name checks across workflows, but the coordination is fragile (if the docs workflow misses an event, the check name doesn't appear and protection blocks).
- **Use `dorny/paths-filter@v3` action** — well-tested but adds supply-chain surface; our 30-line shell step is auditable and dependency-free.

## References

- `.github/workflows/ci.yml` — the `Detect docs-only change` step and the gated step blocks.
- ADR-0003 — the required-check invariant this bypass works around.
- ADR-0011 — concurrency carve-out for main is a related fix discovered after this bypass shipped.
