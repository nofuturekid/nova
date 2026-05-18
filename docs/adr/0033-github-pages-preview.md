# ADR-0033: Publish the interactive UI prototype to GitHub Pages

- **Status**: Proposed
- **Date**: 2026-05-18
- **Tags**: ci, docs, ui

## Context

Prospective users want to see what the app looks like before installing
an APK. The repo already has static screenshots in the README, but a
clickable prototype conveys the navigation/feel far better.

We have a single self-contained HTML prototype
(`docs/preview/index.html`, ~1.7 MB): a bundler export that embeds
React/Babel + the app chunks in an inline `resourceMap`, rehydrated at
runtime via `atob()` → `URL.createObjectURL`. It runs from one file, has
**no backend calls** (only a Google-Fonts `preconnect`, which degrades
to a system font offline) and no secrets — safe to publish.

The blocker: **GitHub does not render committed HTML in the web UI** —
clicking the file on github.com shows source, not the rendered app. So
"users can just see it" requires the file to be *served*, not just
stored. `htmlpreview.github.io`-style tricks work but add an external
dependency this repo otherwise avoids.

## Decision

**Publish `docs/preview/` to GitHub Pages via a dedicated GitHub Actions
workflow**, and link it from the README as a no-install live preview.

- New workflow `.github/workflows/pages.yml`: on push to `main` limited
  to `docs/preview/**` (and the workflow itself) + `workflow_dispatch`.
  Least-privilege permissions (`contents: read`, `pages: write`,
  `id-token: write`) per ADR-0022. Concurrency group `pages`,
  `cancel-in-progress: false` — never cancel an in-flight deploy on main
  (ADR-0011).
- `actions/configure-pages@v5` with `enablement: true` turns Pages on
  (build type "workflow") on first run *through the merged, reviewed
  workflow itself* — no out-of-band repo-settings mutation by a person
  or an agent. Site root = the prototype (`index.html`), served at
  `https://nofuturekid.github.io/UnraidControl/`.
- The prototype is treated as a **version-stamped snapshot**, not living
  documentation. The README link and the page context state the snapshot
  version; it is refreshed deliberately, not kept in lockstep with every
  UI change.
- Actions pinned by major tag, matching existing workflow convention.

## Consequences

**Positive**
- A real, clickable preview at a stable URL; the strongest "what is
  this" answer short of installing, linked right under the screenshots.
- Self-contained artifact: no build step, no backend, no secrets in the
  pipeline; the deploy job only uploads a directory.
- Reuses established hygiene (least-privilege, no-cancel-on-main,
  path-scoped trigger) so it doesn't perturb the app CI.

**Negative / trade-offs**
- **Drift**: a prototype snapshot ages against the live Compose UI. Left
  unmanaged it becomes misleading — mitigated by version-stamping and
  the revisit trigger, not by automation.
- A 1.7 MB single-file asset lives in the repo (acceptable for a docs
  asset; not LFS-worthy at this size, but it is a large opaque blob).
- One more workflow and a Pages environment to keep healthy; enabling
  Pages is still a repo capability change, just performed by the
  reviewed workflow rather than silently.
- The prototype uses in-browser Babel and embedded chunks; it is a
  demo, explicitly not the shipped code path.

**Trigger to revisit**
- The prototype materially diverges from the shipped UI (screens,
  navigation, or branding no longer representative) → refresh
  `docs/preview/index.html` to a new snapshot, or remove the preview and
  this ADR rather than ship a misleading one.
- The preview is ever used to serve anything dynamic or anything that
  could carry secrets/PII → stop; Pages is for the static snapshot only.
- A lower-maintenance representation (e.g. an auto-generated screenshot
  set) supersedes the hand-exported prototype.

## Alternatives considered

**In-repo file + "download and open" note (optionally
`htmlpreview.github.io`).** No Pages, no workflow — but it fails the
actual goal ("users can just *see* it"): a download step or an external
preview proxy is exactly the friction we're removing, and the proxy is
an outside dependency the repo otherwise shuns.

**Screenshots only (status quo).** Lowest maintenance and renders inline
on GitHub, but static images don't convey navigation/feel. Kept as the
inline complement; the live preview is the deeper look, not a
replacement.

**Build the prototype from source in CI.** Highest fidelity to the real
UI, but there is no such source pipeline here and the artifact already
exists self-contained; building one would be net-new scope for a
documentation aid. Out of scope.

## References

- The artifact: `docs/preview/index.html` (self-rehydrating single-file
  bundle; verified self-contained, no backend, no secrets).
- ADR-0022 (least-privilege workflows), ADR-0011 (no cancel-in-progress
  on main), ADR-0029 (CI build-affecting allowlist — this workflow is
  not app-build-affecting).
- README "Screenshots" section, where the live-preview link is added.
