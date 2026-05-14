# ADR-0001: Adopt Architecture Decision Records

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: process, docs

## Context

The project accumulated a dozen significant decisions in its first weeks — CI pipeline shape, release policy, GraphQL scalar adapters, in-app updater architecture, cleanup conventions. Most lived as paragraphs scattered across `CONTRIBUTING.md`, `HANDOFF.md`, and PR descriptions. That works while one person remembers everything, but breaks the moment someone (human or LLM) re-encounters a decision and asks "wait, why are we doing it this way?"

Without a record of the *reasoning* — the alternatives that were ruled out and why — future contributors re-litigate every choice from scratch, often arriving at the same answer for different reasons or undoing a deliberate trade-off they didn't know existed (v0.1.11 → v0.1.14 schema-drift bugs were a partial example: we had to learn lessons twice because the first lesson wasn't written down).

## Decision

Adopt **Architecture Decision Records** under `docs/adr/`, MADR-style (Markdown Any Decision Records). Each ADR captures one decision: context, the decision itself, consequences, alternatives considered, references. ADRs are numbered sequentially (`NNNN-kebab-title.md`) and indexed in `docs/adr/README.md`.

ADRs are **the single source of truth for "why"**. `CONTRIBUTING.md` and `HANDOFF.md` are reduced to practical "how" guidance with pointers to the ADRs.

## Consequences

**Positive**
- New contributors (and AI assistants) can find the rationale for any non-obvious convention without spelunking through PR history.
- Backfilled ADRs surface previously-implicit trade-offs.
- ADRs include a *trigger to revisit* field — a concrete signal that would justify reopening the decision. This makes the records less likely to rot silently.

**Trade-offs**
- One more file to write per major decision. Mitigated by keeping ADRs short (1–2 pages) and templated.
- Risk of ADRs going stale if not updated when superseded. We address this by mandating ADRs flip status to `Superseded by ADR-XXXX` rather than being deleted.

**Trigger to revisit**
- If we find the team writing ADRs that nobody reads, or skipping ADRs for major decisions, the convention isn't pulling its weight — revisit the format or the bar for "what needs an ADR".

## Alternatives considered

- **Keep prose in CONTRIBUTING/HANDOFF only.** Worked initially but fails at scale; rationale gets lost between unrelated paragraphs.
- **GitHub Discussions or wiki.** Lives outside the repo, drifts from the code, no review process.
- **Nygard-style ADRs (heavier sections, mandatory consequences-of-each-alternative).** More structure than needed for a solo+occasional-contributors project; we'd skip writing them under friction. MADR's lighter form wins on adoption odds.

## References

- [MADR project](https://adr.github.io/madr/) — format reference.
- [`docs/adr/template.md`](./template.md) — the template we use.
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) — workflow, refers to ADRs for decisions.
