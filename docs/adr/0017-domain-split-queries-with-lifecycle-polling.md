# ADR-0017: Domain-split queries with lifecycle-aware polling

- **Status**: Proposed
- **Date**: 2026-05-14
- **Tags**: data, ui, performance, resilience

## Context

The app currently runs **one** GraphQL query — `GetServerSnapshot` — every 2 s for the entire lifetime of the `MainViewModel`. It fetches `info`, `array`, `docker.containers`, `vms`, and `metrics` in one request, regardless of which tab the user is on and regardless of whether the app is in the foreground.

Three problems with that shape have surfaced:

1. **Resilience is all-or-nothing.** GraphQL validates the full operation document before executing any selection set. A single unknown field anywhere in the query causes the server to reject the entire response. v0.1.22-beta1 demonstrated this concretely: we added `DockerContainer.updateStatus` to the snapshot query, the live Unraid 7.3 API didn't expose that field, and every screen in the app collapsed to "Can't reach the server" — Overview, Array, VMs included, none of which had any business depending on Docker fields.

2. **Wasted bandwidth, CPU, and battery.** Even when the user sits on the Overview tab (which renders CPU/memory metrics and a system-info block), the app pulls the full container list with ports + mounts + icon URLs every 2 s. On servers with 20+ containers that's a non-trivial payload — and it's pure overhead. Same story for Array disk lists and VM domains when those tabs aren't visible.

3. **Polling rate is one-size-fits-all.** Metrics legitimately want a high refresh rate (1–2 s for smooth sparklines). Array status barely changes between parity-check events — 10 s is fine. System info (hostname, kernel, Unraid version) changes once between reboots — once per connect plus an hourly refresh is enough. The single shared snapshot stream forces the slowest acceptable rate onto everything, *or* the fastest reasonable rate onto everything; either way, something is over- or under-served.

The architecture pre-dates any of these observations — it was reasonable for an MVP with one tab. It is no longer reasonable.

## Decision

**Replace the unified `GetServerSnapshot` with five domain-scoped GraphQL operations, polled independently and only while their data is actually being rendered.** Add app-lifecycle observation so polling pauses entirely when the app is backgrounded.

Concretely:

- **Five queries** in `queries.graphql`:

  | Query | Domain | Active when | Poll interval |
  |---|---|---|---|
  | `GetServerInfo` | `info` (hostname, versions, CPU specs) | App connected | Once on connect + 60 s refresh |
  | `GetMetrics` | `metrics` (CPU %, memory) | Overview tab visible | 2 s (optionally 1 s) |
  | `GetArray` | `array` (disks, parity check) | Array tab visible | 5–10 s |
  | `GetDockerContainers` | `docker.containers` | Docker tab visible **or** a container detail sheet is open | 2–3 s; transiently 1 s during an active update mutation |
  | `GetVms` | `vms.domains` | VMs tab visible | 2–3 s |

- **Repository layer** (`UnraidRepository`) exposes one `Flow` per domain. Each flow internally polls only while it has at least one collector — Kotlin Flows' `WhileSubscribed` semantics give us this for free.

- **ViewModel** (`MainViewModel`) gates each flow on `selectedTab` (and on a separate `dockerSheetOpen` flag for the cross-tab case where a container detail sheet is open while the user navigates away from the Docker tab). When a flow is gated off, its collector is dropped, and the polling loop in the repository naturally suspends.

- **Lifecycle awareness**: the hosting Activity observes `Lifecycle.Event.ON_START` / `ON_STOP` and propagates an `appVisible: StateFlow<Boolean>` into the ViewModel. All gating logic ANDs with `appVisible` — so backgrounded app = zero polls, period.

- **Per-domain cache**: each flow keeps a replay-1 of its last successful payload. Re-entering a tab shows the last-known state instantly (no skeleton flash) while a fresh poll fires in the background.

The five Apollo-generated `Query.Data` types each get their own mapper function in `GraphQlMapper.kt` (`toMetricsStats()`, `toArrayInfo()`, etc.) — same logic as today, just demuxed by query.

Domain models in `data/model/Models.kt` and UI composables do **not** change shape; each tab composable now observes a smaller, tab-specific state instead of a slice of one big `ServerSnapshot`.

## Consequences

**Positive**

- One bad field in one domain no longer breaks the rest of the app. The v0.1.22-beta1 failure mode becomes localised: a Docker schema mismatch shows "Can't load Docker" inside the Docker tab; Overview/Array/VMs keep working.
- Significant bandwidth/CPU savings for the common case (user sitting on one tab). Estimated 3–5× reduction in polled payload volume on Overview, scaling with container count.
- Battery: backgrounded app stops all polling within ~1 s of `ON_STOP`. Today it keeps polling for the full `WhileSubscribed(5_000)` window plus then runs until the ViewModel is destroyed.
- Per-domain rate tuning unlocks better UX: metrics sparklines can run at 1 s without paying the cost of refetching VM domains 60 times per minute.
- Container-update mutation (planned in Phase B of the Docker-update roadmap) can transiently bump the Docker poll to 1 s for instant "Updating → Up to date" feedback, without affecting other domains.

**Negative / trade-offs**

- Roughly five-way state fan-out in the ViewModel. `MainUi` becomes `MainUi(infoUi, metricsUi, arrayUi, dockerUi, vmsUi)` instead of a single snapshot field. Mechanical but touches every tab composable.
- More moving parts in the repository: five polling loops, lifecycle gating, per-domain cache. The unified snapshot's 3-failure resilience window (`UnraidRepository.kt:49`) must be replicated per-domain.
- Tab-switch perceived latency: switching to a tab whose flow was paused means waiting for the first poll to complete before fresh data appears (mitigated by replay-1 cache for second and subsequent visits).
- More request count overall when the user actually flips between tabs — but each request is much smaller, and Apollo's HTTP/2 connection reuse makes the per-request overhead negligible.

**Trigger to revisit**

- If we introduce a GraphQL subscription/WebSocket pathway for any of these domains, the per-domain polling becomes redundant for that domain. At that point: replace the polling flow with a subscription flow, keep the gating layer unchanged.
- If the Unraid GraphQL API gains a partial-execution mode (returning `data` for successful fields plus `errors` for failed ones), the resilience motivation weakens — efficiency and per-domain rates would still justify the split, but the urgency drops.

## Alternatives considered

**Keep the single snapshot, make individual fields nullable.** Marking `updateStatus` nullable on the client schema would have let null values pass — but it doesn't help when the *field itself doesn't exist on the server*. The server-side validator rejects "Cannot query field X" before the document is executed, regardless of client-side nullability. Doesn't address efficiency or rate-tuning either.

**Schema introspection on connect.** App fetches `__schema` once per server, builds queries dynamically to include only fields the server actually exposes. Elegant — single source of truth, no client-side mirror to drift. But: significant complexity (dynamic query construction, no compile-time Apollo type safety, harder testing), and doesn't address efficiency or rate-tuning. Worth revisiting once the codebase matures; overkill today.

**Per-domain queries but keep a single global timer.** Five queries, but all fire at the same 2 s interval. Solves resilience, leaves efficiency and rate-tuning on the table. Halfway measure; if we're already restructuring the data layer, do the lifecycle work in the same PR.

**Status quo with try/catch and second-pass narrower query.** When the big snapshot fails, retry with a stripped-down version excluding Docker. Increasingly brittle as more conditional fields appear, doesn't help efficiency, doesn't help rate-tuning. Considered briefly during the v0.1.22-beta1 fix and rejected.

## References

- The v0.1.22-beta1 incident: `Cannot query field "updateStatus" on type "DockerContainer"` collapsing the entire app to "Can't reach the server", despite the bad field only affecting Docker data. Fixed in PR #25.
- Current snapshot stream implementation: `app/src/main/kotlin/net/unraidcontrol/app/data/repository/UnraidRepository.kt` (`snapshotStream`, ~Z. 60-98).
- Related: the planned long-running Apollo client for update mutations (ADR-0016 *proposed*) — Phase D and that ADR are independent but synergistic.
