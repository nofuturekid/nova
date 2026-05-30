# 0.1.40 — Live metrics & docker stats via GraphQL subscriptions

- **Date**: 2026-05-30
- **Status**: Design approved (brainstorm) — pending spec review
- **Cycle**: 0.1.40 (single big-bang beta2, one on-device acceptance per ADR-0027 Tier 3)
- **Builds on**: ADR-0042 (network-throughput subscription pilot, device-verified 2026-05-29)

## Summary

The network-throughput subscription balloon (ADR-0042) is device-verified working.
This cycle rolls the proven WS subscription substrate out to three more domains, all
confirmed present on the real server by read-only introspection on 2026-05-30:

| Domain | Subscription root | Type | UI today | Work |
|---|---|---|---|---|
| CPU + Memory | `systemMetricsCpu`, `systemMetricsMemory` | `CpuUtilization`, `MemoryUtilization` | Cards exist, 2 s poll | Transport swap + poll-fallback |
| Temperature | `systemMetricsTemperature` | `TemperatureMetrics` | none | New Overview card |
| Docker stats | `dockerContainerStats` | `DockerContainerStats` | `Container.cpu/memMb` scaffolded but hardcoded `0` | Stream-join into container list |

All three reuse the existing `subscriptionStream<D,T>` + `ApolloClientFactory.buildWs`
+ `gatedStream` substrate from ADR-0042. No new dependency, no new TLS trust path.

**Live introspection (2026-05-30, `https://192.168.11.2/graphql`, `x-api-key`):**
the real `Subscription` type exposes `systemMetricsCpu/Memory/Temperature/Network`,
`dockerContainerStats`, plus others (array, ups, parity, notifications). The query-side
`Metrics{cpu: CpuUtilization, memory: MemoryUtilization, temperature: TemperatureMetrics,
network: NetworkUtilization}` returns the **same types** the subscriptions emit — so a
single extended `GetMetrics` poll is the unified fallback for CPU+Mem+Temp.

## Goals / success criteria

1. Overview CPU and Memory cards update from `systemMetricsCpu`/`systemMetricsMemory`
   (live), degrading to the existing 2 s poll on sustained WS failure — no blank card.
2. A new Overview Temperature card shows average + hottest sensor with M3
   warning/critical color, fed by `systemMetricsTemperature`, same poll-fallback.
3. Docker tab rows show live per-container CPU% and memory from `dockerContainerStats`;
   absent (not blank-zero noise) on WS failure.
4. All read-only ops live-verified against the real server before ship; on-device
   acceptance of one combined `0.1.40-beta2` (big-bang) is the promotion gate.
5. The stranded 0.1.40 version bookkeeping (see below) is corrected in this cycle.

## Non-goals

- Per-core CPU telemetry (`systemMetricsCpuTelemetry → CpuPackages`) — out of scope.
- Per-sensor temperature detail / history, docker net/block IO rows — summary/cpu+mem only.
- Array / UPS / parity / notification subscriptions — stay polling.
- Mutations over WS — none; maintainer-only mutation convention unaffected.
- Historical persistence of any series — unchanged (in-memory sparklines only).

## Design

### Substrate (unchanged from ADR-0042)
`subscriptionStream<D,T>(op, map)` keyed on `servers.activeWithKey`,
`flatMapLatest`, cold WS via `buildWs`, `DomainState{NoServer|Error|Content}`.
Gating via `gatedStream(gate, stream)` in `MainViewModel`.

### A. CPU + Memory — transport swap with poll-fallback

- **Ops:** add `systemMetricsCpu { percentTotal }` and
  `systemMetricsMemory { total used buffcache }` to `subscriptions.graphql`
  (select exactly the fields `GetMetrics` already selects — zero mapper drift).
- **Combine:** `combine(cpuSub, memSub)` → existing `LiveMetrics{cpuPercent,
  memTotalGb, memUsedGb, memBuffGb}`. `metricsState` and both cards stay unchanged.
  First combined emit waits for one frame from each sub (~1 Hz → sub-second).
- **Fallback (ADR-0026 mandate — metrics have a query path):** a new generic
  combinator
  ```
  fun <T> subscriptionOrPoll(
      sub: Flow<DomainState<T>>,
      poll: Flow<DomainState<T>>,
      graceMs: Long,
  ): Flow<DomainState<T>>
  ```
  Subscription is primary. It degrades to `poll` when the sub yields sustained
  `DomainState.Error` **or** produces no first `Content` within `graceMs`; it
  returns to the sub when the sub recovers a `Content` frame. The poll is the
  existing `metricsStream()` (`GetMetrics`, 2 s). This is a **new substrate
  capability** — the network balloon had no fallback because it had no query path.
  Implemented as a pure, unit-tested seam (`subscriptionOrPollForTest`) mirroring
  `subscriptionStreamForTest`.
- **Extended `GetMetrics`:** add `temperature { summary { average hottest { name
  current { value unit } } warningCount criticalCount } }` so the poll backs CPU+Mem+Temp.

### B. Temperature — new card

- **Op:** `systemMetricsTemperature { summary { average hottest { name current { value unit } }
  warningCount criticalCount } }`. `TemperatureReading{value: Float, unit:
  TemperatureUnit, status: TemperatureStatus}`; `average` is a bare `Float` (unit
  inferred from readings — confirm on device).
- **Model:** new `Temperature(average, unit, hottestName, hottestValue, warningCount,
  criticalCount)` mapped from `TemperatureSummary`. (Per-sensor list deferred.)
- **State:** `temperatureState = gatedStream(overviewOnly(),
  subscriptionOrPoll(tempSub, tempPoll, …))`, same gate as metrics.
- **UI:** new Overview `StatCard` (same component as CPU/Mem/Network) showing average
  with hottest-sensor subtitle; color escalates to M3 warning/critical roles when
  `warningCount`/`criticalCount` > 0 (published M3 tokens, Rule 13). Maintainer's
  on-device acceptance is the visual gate.

### C. Docker container stats — stream-join overlay

- **Op:** `dockerContainerStats { id cpuPercent memUsage memPercent netIO blockIO }`
  (no args — streams one container per frame).
- **Stream:** `dockerStatsStream(): Flow<DomainState<Map<String, ContainerLiveStats>>>`
  — subscribes, accumulates a `Map<containerId, ContainerLiveStats>` across frames,
  emits the growing map. New model `ContainerLiveStats(cpuPercent, memPercent,
  memUsage)` (cpu%+mem% Floats; `memUsage` is a preformatted String from the server).
- **Join:** in `MainViewModel`, `combine(dockerState, dockerStatsState)` overlays
  stats onto the polled container list **by id** — a separate overlay map, the polled
  `Container` object is **not** mutated (keeps poll and WS paths decoupled).
- **UI:** `DockerTab` rows render live CPU% and memory from the overlay; rows without a
  stats frame yet (or on WS failure) simply omit live stats — no zero noise.
- **Fallback: none** — `DockerContainer` query has no stats fields (introspection
  confirmed). Honest "no live stats" on WS failure, same stance as network.
- **Gating:** `gatedStream(dockerGate(), dockerStatsStream())` — WS lives only while the
  Docker tab is foreground.

### Cross-cutting

- **Schema vendoring:** add to `schema.graphqls` the Subscription roots
  `systemMetricsCpu/Memory/Temperature` + `dockerContainerStats`, and the new types
  `TemperatureMetrics/TemperatureSummary/TemperatureSensor/TemperatureReading/SensorType`
  and `DockerContainerStats`. `CpuUtilization`/`MemoryUtilization` already vendored.
- **Two transports in production:** WS now carries network+cpu+mem+temp+docker; HTTP
  poll remains for all other domains and as the metrics fallback floor.
- **ADR-0043 (new):** records the rollout from balloon → multi-domain, the
  `subscriptionOrPoll` fallback combinator, and the docker stream-join. ADR-0042 stays
  the network-balloon record; ADR-0043 references it as predecessor.

## Version bookkeeping defect (must be fixed in this cycle)

`v0.1.40-beta1` was tagged on `main` commit `2fc3194`, but that squash-merge (#200)
**omitted** the release commit. Consequences on `main` / the tag today:

- `build.gradle.kts` still says `versionCode 107 / 0.1.39` — **collides with the
  0.1.39 stable**; the beta1 artifact mis-reports its own version and no updater
  (F-Droid) would see it as newer.
- `CHANGELOG.md` 0.1.40 entry, `ADR-0042`, and the `ADR-0026` supersede note live only
  in two un-pushed local commits (`3206c25`, `fd25a4a`).

This cycle lands those four files and cuts a correctly-versioned `0.1.40-beta2`
(versionCode bumped, `0.1.40-beta2`). The broken `v0.1.40-beta1` tag is left untouched
in history (per maintainer decision: non-destructive — beta2 supersedes it).

## Testing strategy (CI unit tests + live + on-device)

- **Pure seams (CI):** `subscriptionOrPoll` degrade/recover transitions; cpu+mem
  `combine` → `LiveMetrics`; `TemperatureSummary` → `Temperature` mapping; docker
  frame-accumulation into the overlay map and join-by-id. Each test encodes *why*
  (Rule 9): fallback prevents a blank card; join must not drop un-stat'd containers.
- **Live (pre-ship):** `graphql-smoke` for the extended `GetMetrics` (HTTP); read-only
  WS probe for the four subscription deliveries (the smoke skill is HTTP-only).
- **On-device (gate):** one `0.1.40-beta2`, maintainer acceptance of all three live
  surfaces (ADR-0027 Tier 3). If any domain fails live: stop, diagnose, do not promote.

## Build sequence (delegated execution tail — Rule 14)

1. Vendor schema + add 4 subscription ops + extend `GetMetrics`.
2. Models + mappers (`Temperature`, `ContainerLiveStats`); pure-logic unit tests.
3. Repository: `subscriptionOrPoll`, cpu/mem combine, `temperatureStream`,
   `dockerStatsStream`.
4. ViewModel: `metricsState` via fallback, `temperatureState`, docker stats join.
5. UI: Temperature `StatCard`; `DockerTab` live cpu%/mem rows.
6. ADR-0043 + CHANGELOG + version bump (108→ corrected) + ADR-0026/0042 fixups.
7. Live-verify, branch/PR, CI, cut `0.1.40-beta2`, await on-device acceptance.

## Alternatives considered

- **Split `metricsState` into cpu/mem streams** — rejected; more VM/UI churn, the
  `combine`→`LiveMetrics` keeps both cards and the model untouched.
- **No metrics fallback (network-style)** — rejected; metrics have a query path, so
  ADR-0026 mandates fallback; a blank CPU card on a transient WS drop is unacceptable.
- **Mutate polled `Container` with stats** — rejected; couples the two transports;
  the overlay map keeps them independent and testable.
- **Incremental betas (beta2/3/4 per domain)** — considered; maintainer chose big-bang
  (one combined beta2, ADR-0030 precedent) to minimise device-acceptance rounds.
