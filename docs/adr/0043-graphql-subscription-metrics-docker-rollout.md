# ADR-0043: Roll the GraphQL subscription transport out to CPU, memory, temperature, and docker stats

- **Status**: Proposed
- **Date**: 2026-05-30
- **Tags**: data, api, performance

## Context

ADR-0042 shipped a scoped test balloon — the WebSocket subscription transport
wiring *one* domain, network throughput — device-verified on the maintainer's
real Unraid server (`0.1.40-beta1`, 2026-05-29). With the substrate proven, a
read-only `graphql-transport-ws` session on 2026-05-30 confirmed the four next
domains deliver: `systemMetricsCpu: CpuUtilization!`, `systemMetricsMemory:
MemoryUtilization!`, `systemMetricsTemperature: TemperatureMetrics` (**nullable
root** — tolerate null frames), and `dockerContainerStats: DockerContainerStats!`
(no args — one container per frame, accumulated by id).

Unlike network throughput, CPU/Mem/Temp have a query path: the `Metrics` query
exposes `cpu`/`memory`/`temperature` of the same payload types the subscriptions
emit. ADR-0026's mandate — automatic fallback to the existing poll for any domain
that has one — therefore applies. Docker stats, like network, have no query path
and so have no fallback.

## Decision

Roll the ADR-0042 substrate out to four more domains, reusing
`subscriptionStream<D,T>`, `ApolloClientFactory.buildWs`, and `gatedStream`
unchanged, and adding two new substrate capabilities: a generic subscription→poll
fallback combinator and a docker stream-join overlay.

- **`subscriptionOrPoll`** — the ADR-0026 fallback, implemented as a pure,
  unit-tested combinator (the exact merged signature):

  ```kotlin
  internal fun <T> subscriptionOrPoll(
      sub: Flow<DomainState<T>>,
      poll: Flow<DomainState<T>>,
      graceMs: Long = SUB_FALLBACK_GRACE_MS,
  ): Flow<DomainState<T>>
  ```

  Subscription primary; `NoServer` forwarded immediately; degrades to `poll` on
  sustained `Error` or no first `Content` within `graceMs` (= `SUB_FALLBACK_GRACE_MS`
  = `6_000L`, 3× the 2 s metrics poll); returns to the sub on recovery (emitting
  the recovered frame directly). Poll collected only while in fallback.

- **CPU + Memory** — `combine(cpuSub, memSub)` → existing `LiveMetrics` via
  `combineMetricsStates` (Content only when both legs are Content with matching
  server URL); the combined stream is the primary of a `subscriptionOrPoll` whose
  poll is the current `GetMetrics`. `metricsStream()` keeps its signature; cards
  untouched.
- **Temperature** — new `Temperature` model + Overview `StatCard` escalating to
  the M3 warning/critical roles; `subscriptionOrPoll(tempSub, tempPoll)`; the
  extended `GetMetrics` backs the poll. Nullable root tolerated.
- **Docker stats** — `dockerStatsStream()` accumulates `Map<id,
  ContainerLiveStats>`; `MainViewModel.joinContainerStats` LEFT-joins it onto the
  polled list BY ID without mutating `Container`, preserving polled order. Rows
  omit live stats when absent. No fallback.

Supersedes ADR-0026 for cpu/mem/temp/docker-stats; builds on ADR-0042.

### Temperature sensor filtering (beta3)

On-device data from the real server (2026-05-30) showed the temperature card
reading "~91°, hottest CPU Fan 1753°". Root cause: the server (`unraid/api`)
reports EVERY lm-sensors channel under `systemMetricsTemperature` — including a
fan tach (value = RPM) and voltage rails (value = Volts) — all mislabeled
`unit: CELSIUS` and `type: CUSTOM`. The server-computed `summary` we displayed
verbatim therefore named the fan as "hottest" and inflated the average.

Fix (maintainer-approved "type + plausibility band"): stop trusting the server
summary. Both temperature paths now select the raw `sensors` list and the
client recomputes hottest/average/warning+criticalCount, keeping a channel as a
real temperature iff `value <= MAX_PLAUSIBLE_C (130) && (type != CUSTOM ||
value >= MIN_PLAUSIBLE_C (8))` — the upper cap drops fan RPM regardless of type,
the lower floor drops voltage rails but only for the untyped `CUSTOM` catch-all.
Empty kept set → `Temperature.UNKNOWN`. The mislabeling should be reported
upstream to `unraid/api`; the band is the client-side guard until it is fixed.

## Consequences

**Positive:** the most aggressive poll (metrics, 2 s) becomes push for CPU/Mem
with the poll as the automatic floor; a temperature card and per-container live
stats become possible at all; `subscriptionOrPoll` is a reusable capability;
reuses ADR-0042's transport/gating/TLS pin, no new dependency.

**Negative / trade-offs:** five domains on WS now (more lifecycle surface); the
nullable temperature root must be treated as "no reading yet", not an error; no
fallback for docker stats; `graphql-smoke` is HTTP-only so WS delivery is
validated by a read-only probe + on-device acceptance; the `schema.graphqls`
subset grows.

**Trigger to revisit:** any live surface failing on the real server after
on-device acceptance of `0.1.40-beta2`; `subscriptionOrPoll` flapping on a
marginal link (revisit `graceMs`).

**Out of scope:** per-core CPU, per-sensor temperature detail/history, docker
net/block IO rows; array/UPS/parity/notification subscriptions; mutations over WS.

## Alternatives considered

Split `metricsState` into cpu/mem streams (rejected — more churn); no metrics
fallback network-style (rejected — metrics have a query path); mutate the polled
`Container` (rejected — couples the transports); join in the VM via a single
paired `combine(dockerState, dockerStatsState)` returning a paired
`DomainState` (rejected — would ripple DockerTab's `state` type and two
MainScreen call sites; instead the overlay is a separate `dockerLiveStats` map
joined in `DockerContent` via the tested `joinContainerStats`); incremental
per-domain betas (rejected — maintainer chose one big-bang beta2, ADR-0030
precedent).

## References

- ADR-0042 — network-throughput subscription pilot (predecessor).
- ADR-0026 — subscriptions hybrid (superseded here for cpu/mem/temp/docker).
- ADR-0017 — domain-split lifecycle polling (the metrics poll survives as the floor).
- ADR-0041 — self-signed TOFU trust (the pin the WS reuses).
- ADR-0027 — Tier-3 on-device acceptance gate. ADR-0006 — beta-first.
- Live verification: read-only `graphql-transport-ws`, `https://192.168.11.2/graphql`, `x-api-key`, 2026-05-30.
