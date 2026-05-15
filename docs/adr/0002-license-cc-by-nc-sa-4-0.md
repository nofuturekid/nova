# ADR-0002: License under CC BY-NC-SA 4.0

- **Status**: Superseded by [ADR-0021](./0021-relicense-to-gpl-3.md)
- **Date**: 2026-05-13
- **Tags**: legal

## Context

The app is a hobby project intended to be:
- Freely usable by anyone with an Unraid server.
- Modifiable and forkable, with modifications visible to the community.
- **Not** repackaged and sold commercially without permission.

Standard permissive OSS licenses (MIT, Apache-2.0) allow unrestricted commercial reuse — including someone wrapping the app, adding a paywall, and reselling it. Standard copyleft (GPL-3.0) prevents that but is built for software-code reuse, not the broader "creative work" framing we wanted.

## Decision

License the project under **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0)**.

- **BY** — attribution required.
- **NC** — no commercial use without separate permission.
- **SA** — derivatives must use the same license (copyleft for forks).

Commit text: `LICENSE` at repo root with the standard CC license body. README and CONTRIBUTING reference it.

## Consequences

**Positive**
- Hobby fork → fine. Personal use → fine. Pull request → fine.
- A company can't take the source, repackage with paid features, and ship it on the Play Store without negotiating with us.
- ShareAlike means forks stay open, addressing the "company makes proprietary improvements" risk.

**Trade-offs**
- CC licenses are designed for creative works (photos, articles, music). Using them on code is legally valid but unconventional. Some downstream tooling (OSI license databases, GitHub's auto-detection) treats CC-NC as "non-open-source" and may surface warnings.
- "Commercial use" is the most-litigated phrase in CC-NC; ambiguity around what counts as commercial (e.g. "freelancer uses it on a client's NAS"). We accept this — we'd rather get DMs asking permission than ship under MIT and have no leverage.

**Trigger to revisit**
- If we ever want to push the app to F-Droid (which prefers OSI-approved licenses), or if a corporate contributor balks at CC-NC.

## Alternatives considered

- **MIT / Apache-2.0** — too permissive; explicitly allows the resale scenario we want to block.
- **GPL-3.0 / AGPL-3.0** — copyleft works but doesn't capture "creative work" framing; AGPL also forces network-service-providers to publish source which is overkill for a phone app.
- **PolyForm Noncommercial** — purpose-built for source-available + noncommercial, but is less well-known than CC and harder for non-developers to evaluate.
- **No license (proprietary)** — defeats the "freely usable" requirement.

## References

- [`LICENSE`](../../LICENSE)
- [Creative Commons CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)
- [PolyForm Noncommercial](https://polyformproject.org/licenses/noncommercial/1.0.0/) (rejected alternative)
