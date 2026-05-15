# Architecture Decision Records

We record significant architecture decisions as ADRs. They're the **single source of truth for "why we did it this way"** — `CONTRIBUTING.md` and `HANDOFF.md` describe *what to do*, ADRs explain *why the rule exists*.

## Format

Lightweight MADR-style. See [`template.md`](./template.md). Filename: `NNNN-kebab-case-title.md`, numbered sequentially.

Each ADR has: status, date, context, decision, consequences (incl. a **trigger to revisit**), alternatives considered, references.

## When to write one

Before merging any of:

- Changes to the CI/CD pipeline shape (workflow structure, gating, promotion model)
- Release-process changes (versioning, tag conventions, pre-release policy)
- App architecture (module boundaries, data layer, navigation, DI scope)
- API integrations (schema mapping, scalar adapters, auth model)
- Permissions, manifest entries, security posture
- Anything that creates a non-obvious convention future contributors would otherwise re-litigate

Single-file bug fixes and visual tweaks don't need an ADR. If you're unsure, write one — they're cheap.

## Workflow

1. Branch as usual (`docs/adr-NNNN-…` or part of the feature branch).
2. Copy `template.md` to `NNNN-kebab-title.md`.
3. Status `Proposed` while in PR review; flip to `Accepted` at merge time.
4. Link the ADR from the PR description.
5. Update this index.

ADRs live under `docs/` so the CI docs-only bypass applies — pure-ADR PRs complete in ~10 s and don't need a version bump.

## Index

| # | Title | Status |
|---|---|---|
| [0001](./0001-adopt-architecture-decision-records.md) | Adopt Architecture Decision Records | Accepted |
| [0002](./0002-license-cc-by-nc-sa-4-0.md) | License under CC BY-NC-SA 4.0 | Accepted |
| [0003](./0003-pr-gate-required-status-check.md) | PR-gate workflow with required CI status check on main | Accepted |
| [0004](./0004-build-once-promote-pipeline.md) | Build-once-promote pipeline (CI builds APK, Release tag downloads it) | Accepted |
| [0005](./0005-prerelease-tag-convention.md) | Pre-release tag convention `-beta1` / `-rc1` (trailing digit required) | Accepted |
| [0006](./0006-pragmatic-beta-first-release-policy.md) | Pragmatic risk-categorised beta-first release policy | Accepted |
| [0007](./0007-apollo-json-scalar-anyadapter.md) | Apollo JSON scalar via built-in `AnyAdapter` | Accepted |
| [0008](./0008-in-app-updater-packageinstaller.md) | In-app updater via `REQUEST_INSTALL_PACKAGES` + `PackageInstaller` | Accepted |
| [0009](./0009-docs-only-ci-bypass.md) | Docs-only CI bypass via path diff + step gating | Accepted |
| [0010](./0010-release-cleanup-keeps-tags.md) | Release cleanup keeps tags (never `--cleanup-tag`) | Accepted |
| [0011](./0011-cancel-in-progress-only-on-pr-branches.md) | `cancel-in-progress` only on PR branches, not main | Accepted |
| [0012](./0012-install-from-settings-duplicate-vs-singleton.md) | Install-from-Settings duplicates install pipeline (vs. singleton refactor) | Accepted |
| [0013](./0013-versionname-includes-prerelease-suffix.md) | `versionName` includes the pre-release suffix | Accepted |
| [0014](./0014-single-ci-run-per-pr.md) | Single CI run per PR — release workflow resolves through PR | Accepted |
| [0015](./0015-promotion-requires-new-commit.md) | Promotion to a different version-string requires a new commit | Accepted |
| [0016](./0016-long-running-apollo-client.md) | Separate Apollo client for long-running mutations | Proposed |
| [0017](./0017-domain-split-queries-with-lifecycle-polling.md) | Domain-split queries with lifecycle-aware polling | Accepted |
| [0018](./0018-gradle-cache-warm-on-main.md) | Populate the Gradle build cache on push to main | Accepted |
| [0019](./0019-parallel-debug-release-jobs.md) | Split CI into parallel debug + release jobs | Accepted |
| [0020](./0020-migrate-to-ksp2.md) | Migrate from KSP1 to KSP2 | Accepted |
