---
name: adr-new
description: Scaffold the next numbered ADR in docs/adr from template.md. Use when creating a new architecture decision record.
disable-model-invocation: true
---

# adr-new — scaffold the next ADR

Create the next sequentially-numbered Architecture Decision Record under
`docs/adr/`, copied from the repo template and pre-filled with the metadata
the author can verify. ADRs are the single source of truth for *why* a
convention exists (see `docs/adr/README.md`).

## Steps

1. **Pick the number.** Scan `docs/adr/` for files matching `NNNN-*.md`
   (zero-padded 4-digit prefix). Ignore `README.md` and `template.md`. Take
   the highest existing number, add 1, and zero-pad to 4 digits — that is
   `NNNN` for the new ADR.

2. **Copy the template.** Copy `docs/adr/template.md` verbatim to
   `docs/adr/NNNN-<kebab-title>.md`. The title is the decision in **imperative
   mood**, kebab-cased (e.g. `0046-cache-server-icons-on-disk.md`). Match the
   filename convention in `README.md` (`NNNN-kebab-case-title.md`).

3. **Fill the metadata only.** Edit the new file's header:
   - Title line: `# ADR-NNNN: <Short title>` — imperative mood.
   - **Status**: default `Proposed` (it flips to `Accepted` at merge time per
     `README.md`).
   - **Date**: today's date in `YYYY-MM-DD`. If today's date is unknown,
     **ask** — do NOT invent or guess a date.
   - **Tags**: pick from the set already in use across the repo's ADRs
     (e.g. `ci`, `release`, `process`, `ui`, `data`, `graphql`, `security`,
     `docs`). Use the tag(s) that fit; don't invent new categories casually.

4. **Update the index.** If `docs/adr/README.md` contains the ADR index table,
   add a row for the new ADR in numeric order (it sorts last). Match the
   existing table format: `| [NNNN](./NNNN-kebab-title.md) | Title | Status |`.

5. **Leave the body as prompts.** Do NOT write Context / Decision /
   Consequences / Alternatives considered / References. Leave the template's
   prompt text in place for the author to fill in. The skill scaffolds; the
   human writes the substance.

Keep edits surgical — only the new file plus the one index row. Report the
chosen number and the new file path back to the author.
