# ADR-0021: Relicense from CC BY-NC-SA 4.0 to GPL v3

- **Status**: Accepted
- **Date**: 2026-05-15
- **Tags**: legal
- **Supersedes**: [ADR-0002](./0002-license-cc-by-nc-sa-4-0.md) (CC BY-NC-SA 4.0)

## Context

ADR-0002 originally licensed the project under **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0)**. The goals it tried to balance — keep the source open and forkable, prevent a third party from reselling repackaged binaries — were reasonable. The license choice was the weak link.

Three concrete problems with CC BY-NC-SA for this project:

1. **CC explicitly recommends not using CC licenses for software.** The terms are written for "works" (text, images, audio) and map awkwardly onto source code, object code, and combined-work scenarios. From <https://creativecommons.org/faq/#can-i-apply-a-creative-commons-license-to-software>: *"We recommend against using Creative Commons licenses for software."*

2. **`NC` is the most-litigated clause in CC**, and "commercial use" is poorly defined for software. CC's own guidance leaves it to the licensor to clarify case by case. That's a known source of legal headaches and chilling effects on legitimate users (e.g. a freelancer running the app on their own server is unclear-case territory).

3. **Distribution-channel friction.** F-Droid only accepts OSI-approved licenses; CC BY-NC-SA isn't OSI-approved (the NC clause is the deal-breaker). The project's eventual F-Droid roadmap is blocked by the current license. Some other ecosystems (NixOS packaging, Debian) have similar restrictions.

Additionally, the author intends to monetize the published Play Store / F-Droid distribution at some point. As copyright holder the author can already do this regardless of the public license — but having a clean, well-understood public license under which the *source* is available makes the commercial side cleaner too: contributors know what they're agreeing to, downstream users know what they can do, and the line between "public open-source code" and "commercial APK distribution" is drawn at the binary, not at a vague NC clause.

## Decision

**Relicense the project under the [GNU General Public License v3.0 (GPL-3.0)](https://www.gnu.org/licenses/gpl-3.0.html).**

Concretely:

- `LICENSE` at the repo root is replaced with the canonical GPL v3 text from the SPDX license corpus (`GPL-3.0-only.txt`).
- ADR-0002's status flips to `Superseded by ADR-0021`; its body is preserved so the original reasoning is auditable.
- `README.md`, `CONTRIBUTING.md`, `HANDOFF.md` and the ADR README index update their license sections / badges / single-line summaries to point to GPL v3.
- No per-file license headers are introduced. The repo-root `LICENSE` is sufficient under GPL's "either by inclusion in the source code distribution or otherwise" standard, and per-file headers would balloon the diff without legal benefit for a single-author project on GitHub.
- Effective on merge of this ADR's PR. All commits from then forward are GPL v3.

The copyright holder (the author) retains the right to additionally license their own work under separate commercial terms — for instance, distributing the compiled APK on Play Store as a paid app — without needing source disclosure beyond what's already public on GitHub. GPL doesn't restrict the licensor, only what *others* may do with the licensed code.

## Consequences

**Positive**

- **F-Droid path unblocked.** GPL v3 is on F-Droid's accepted-license list.
- **Rip-off protection retained.** Copyleft means anyone who ships a fork has to publish their fork's source under the same license. Someone forking the app and shipping a competing build on Play Store has to also publish the source for that build — practical disincentive without absolute prohibition.
- **Conventional choice.** GPL is the canonical copyleft license for "commercial-friendly open source". Every Android developer, every reviewer at Play / F-Droid, every contributor recognizes it. No quirky CC-NC explanations needed.
- **Author can monetize.** The compiled APK can be sold on Play Store. The source on GitHub stays open. These two facts are well-understood under GPL and have decades of precedent (Wireshark, GIMP historically, many others).
- **Compatibility with most dependency licenses** we use (Apache-2 is GPL-3-compatible, MIT is compatible). Compose, Hilt, Apollo, Coil — all permissive — fit fine.

**Negative / trade-offs**

- **GPL is "viral"** for derived works: if someone embeds parts of this source in their own larger project, that whole project becomes GPL-licensed (or they have to factor out the GPL parts). That's the *intended* property — but it does mean a permissive-licensed downstream project can't trivially borrow snippets from us. Acceptable given the goals.
- **License-change rights for existing contributions.** Today this is solo-author; relicensing is straightforward. Once we have outside contributors, future relicensing requires their consent (or a CLA in advance). Worth keeping in mind if a CLA discussion comes up.
- **The "creative work" framing in ADR-0002 is lost.** GPL is strictly a software license; CC's framing covered things like the README, screenshots, icons, etc. as well. For non-code assets we'll be implicitly relying on "all rights reserved" copyright (which is fine for a single-author project) until or unless we explicitly dual-license assets.

**Trigger to revisit**

- If the project takes an external corporate contributor who refuses GPL terms — typically they want Apache-2 or MIT. We'd evaluate whether to switch (loss of copyleft protection) or to keep them out (probably the right call for an indie app).
- If we ever need to ship as a closed-source SDK to another product, GPL prevents that. Not on the current roadmap; revisit only on a specific deal.

## Alternatives considered

**Stay on CC BY-NC-SA 4.0.** Familiar to the author from CC-licensed creative work, blocked everything else.

**MIT / Apache 2.0.** Permissive, simple, broadest compatibility — but explicitly allows the "fork and resell as closed-source competitor" scenario ADR-0002 wanted to prevent. Same rationale that led to copyleft is still valid; the only change is preferring software-appropriate copyleft over a CC variant.

**AGPL v3.** GPL plus a network-use clause that forces source disclosure when the software is offered as a network service. Designed for SaaS / hosted-software protection. Overkill for a phone app that runs entirely on the user's device — AGPL's hook never fires for our use case, but adds an extra hurdle for downstream contributors. Skipped.

**Dual-licensing** (open-source + commercial). Technically straightforward (the author can dual-license at will as copyright holder) but adds operational complexity (sales pipeline, license-grant negotiation) we don't need at this scale. The single-license GPL path leaves the commercial-APK option open without requiring any of that infrastructure today. If a real commercial-licensing demand appears later, we can add a parallel commercial license without changing the GPL track.

**Source-available licenses (BUSL, PolyForm Noncommercial, Elastic License v2).** Each has narrower acceptance than GPL, blocks F-Droid, and addresses the same goals less conventionally. Reviewed and rejected for the same reason CC was rejected here — uncommon licenses create friction for everyone.

## References

- [`LICENSE`](../../LICENSE) — full GPL v3 text from SPDX.
- [GNU GPL v3.0 canonical page](https://www.gnu.org/licenses/gpl-3.0.html).
- [GPL v3 FAQ](https://www.gnu.org/licenses/gpl-faq.html) — covers the common questions (linking, commercial use, app stores).
- [SPDX identifier](https://spdx.org/licenses/GPL-3.0-only.html): `GPL-3.0-only`.
- Superseded: [ADR-0002 — License under CC BY-NC-SA 4.0](./0002-license-cc-by-nc-sa-4-0.md).
