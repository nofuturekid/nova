# ADR-0044: Surface live CPU/Mem/Net/Disk in the container detail sheet from the docker stats overlay

- **Status**: Accepted
- **Date**: 2026-05-30
- **Tags**: ui, data

## Context

ADR-0043 wired the `dockerContainerStats` subscription into a `dockerLiveStats:
Map<id, ContainerLiveStats>` overlay and joined it onto the Docker TAB rows
(compact cpu% + memory). The container DETAIL sheet, however, rendered its CPU
and Memory `StatBlock`s straight off the polled `Container` (`c.cpu`,
`c.memMb`) — fields the `GetDockerContainers` query never populates, so they are
hardcoded to 0. On-device (beta5, 2026-05-30) the sheet therefore showed CPU
`0.0%` and Memory `—` for every container, including running ones.

Separately, the subscription op already selects `netIO` and `blockIO` (since
beta2 — "RX / TX" and "read / write" preformatted strings the server emits), but
`ContainerLiveStats` only carried cpu/mem, so the mapper silently dropped both.
ADR-0043 listed "docker net/block IO rows" as out of scope; this ADR brings them
in for the detail sheet.

## Decision

Feed the detail sheet the same `dockerLiveStats` overlay the rows use, and carry
net/disk I/O through to it.

- `ContainerLiveStats` gains `netIO: String` and `blockIO: String`; the
  `toContainerLiveStat` mapper maps the already-selected fields through (no
  GraphQL/schema/op change).
- `ContainerDetailSheet` takes a `liveStats: ContainerLiveStats?` param;
  `MainScreen` passes `dockerLiveStats[shown.id]` (reactive — the open sheet
  recomposes as new frames land). The Info tab's StatBlocks now read CPU/Memory
  from `liveStats` (gated on `Running` + a present frame, else `—`) and add two
  more — Network (`netIO`) and Disk I/O (`blockIO`) — displaying the server's
  preformatted strings verbatim. `c.cpu`/`c.memMb` are no longer referenced in
  the sheet (the model fields stay; out of scope here).

Builds on ADR-0043.

## Consequences

**Positive:** the detail sheet shows real live CPU/Mem and, for the first time,
per-container network and disk I/O, all updating in real time off the existing
overlay — no new transport, query, or poll.

**Negative / trade-offs:** stopped containers (no frame) show `—` across all
four blocks — correct (the stats are meaningless) but a visible blank for an
exited container; the StatBlock value typography drops from `headlineMedium` to
`titleLarge` so the wider "used / limit" / "RX / TX" strings fit one ellipsized
half-width line; the Compose UI stays device-gated (no unit test), consistent
with the other cards.

**Trigger to revisit:** if a future op exposes per-container stats on the poll
path (then the sheet could degrade without a live frame); if the server's I/O
string format changes such that verbatim display no longer reads cleanly.

## Alternatives considered

Keep reading `c.cpu`/`c.memMb` and backfill them in the join (rejected — couples
the polled `Container` to the live transport, the exact coupling ADR-0043's
overlay avoids). Add net/disk as a separate model from cpu/mem (rejected — they
arrive in the same frame; one model is simpler). Format netIO/blockIO
client-side into split RX/TX rows (rejected — the server preformats them and the
Docker tab already shows server strings verbatim; deferred until a real need).

## References

- ADR-0043 — metrics/docker subscription rollout (predecessor; the overlay).
- ADR-0042 — network-throughput subscription pilot (the transport substrate).
- ADR-0027 — Tier-3 on-device acceptance gate (the Compose UI's verification).
- Live data: read-only `dockerContainerStats`, real Unraid server, 2026-05-30.
