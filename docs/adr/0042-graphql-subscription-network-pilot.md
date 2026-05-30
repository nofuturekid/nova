# ADR-0042: GraphQL subscription transport — network throughput pilot

- **Status**: Proposed
- **Date**: 2026-05-30
- **Tags**: data, api, performance

## Context

ADR-0026 piloted a generic WebSocket subscription substrate (E0 transport + E1
notifications) against the real Unraid server in 0.1.29-beta9…beta13, then
reverted it. The revert was **provisional** — gated on two concrete triggers, both
of which have now fired:

1. **The API now exposes network throughput.** ADR-0026's 2026-05-17 amendment
   declared network throughput "impossible regardless of transport" because
   `type Metrics { id cpu memory temperature }` and `type Network { id accessUrls }`
   contained no rate fields. That claim is now stale. A new `systemMetricsNetwork`
   subscription field has since been added upstream, delivering
   `NetworkInterfaceUtilization { iface rxBytesPerSec txBytesPerSec }` for every
   interface at ~1 Hz. **Live-verified 2026-05-29** against the real server
   (`wss://192.168.11.2/graphql`, subprotocol `graphql-transport-ws`, auth via
   `x-api-key`): rates deliver, near-instant first event (~sub-second from subscribe
   to first usable value), interfaces include `lo`/`eth0`–`eth3` with live byte rates.

2. **The agent can now drive the real server directly.** A read-only
   `graphql-transport-ws` python client session against the production server
   collapsed the iteration cost that caused the revert in ADR-0026 — hypothesis
   validation no longer requires a multi-beta user-relay loop.

The Overview dashboard already renders a fully-scaffolded Network `StatCard`
(same component as CPU/Memory) with sparkline series — but all inputs are
hardcoded to zero. The feature is to wire it to real data, not to build new UI.

The WS transport itself was validated in the ADR-0026 pilot: the
`graphql-transport-ws` connection establishes and authenticates with the same
`x-api-key` via `connectionPayload`. The self-signed TLS pin established by
ADR-0041 (`TlsTrust.LocalPinned`, `LocalPinningTrustManager`) is already in use
for HTTP polling; the WS can reuse the same OkHttpClient trust path.

This revisit is explicitly scoped to **network throughput only**. Metrics
(CPU/Mem/Temp), `dockerContainerStats`, and `arraySubscription` remain on their
existing poll paths and are out of scope for this beta.

## Decision

**Adopt the WebSocket subscription transport for network throughput only, shipped
as a scoped test balloon to re-validate the subscription substrate on real hardware
before broader rollout.**

Concretely:

- **New `ApolloClientFactory.buildWs(baseUrl, apiKey, trust)`**: a
  WebSocket-capable `ApolloClient` alongside the existing HTTP clients, cached
  per `(endpoint, key, trust-variant)` exactly like `build`/`buildLongRunning`.
  Uses `WebSocketNetworkTransport` with `GraphQLWsProtocol` and
  `connectionPayload = { "x-api-key": apiKey }` (same credential as HTTP). The WS
  engine is backed by an OkHttpClient built with the same `TlsTrust`/pinning logic
  as the private `buildOn(...)` helper — **no new trust path**; the WS reuses the
  ADR-0041 self-signed pin the HTTP poll already established.

- **Generic `subscriptionStream<T>` in `UnraidRepository`**: mirrors `domainStream`
  (ADR-0017) — keyed on `servers.activeWithKey`, `distinctUntilChanged`,
  `flatMapLatest`, same `DomainState` shape (`NoServer`/`Error`/`Content` with
  `withBaseUrl`). Cold flow: the WS socket opens when collected and closes when
  collection is cancelled (gate flip, leaving Overview, app background). On
  sustained WS failure, emits `DomainState.Error`. **No poll fallback** — none
  exists for network throughput (there is no query path for byte rates), so on WS
  failure the card shows "unavailable". This diverges from the ADR-0026 mandate of
  mandatory fallback, which applied to domains that had a query path; network does
  not.

- **`networkThroughputStream()` consumer**: resolves the primary NIC name once per
  connection via the existing `GetNetworkInterfaces` query
  (`primaryNetwork.name`), then subscribes `SystemMetricsNetworkSubscription` and
  maps each frame via `selectThroughput(samples, primaryIface)` to
  `NetworkThroughput(rxBytesPerSec, txBytesPerSec)`. Interface-selection
  preference: named management interface → first non-`lo` active interface → first
  non-`lo` → `NetworkThroughput.ZERO`. `lo` is never the chosen interface.

- **`MainViewModel.networkThroughputState`**: gated with `overviewOnly()` — the
  same gate as `metricsState` — so the WS socket lives only while Overview is the
  foreground tab. `gatedStream` provides `WhileSubscribed(5s)` caching and the
  `resetIfForeignServer` guard unchanged.

- **Apollo `apollo-runtime` 5.0.0** already provides `WebSocketNetworkTransport`
  backed by OkHttp — **no new dependency**.

- The generic `subscriptionStream` substrate is added and available for future
  consumers; only `networkThroughputStream` uses it in this beta.

## Consequences

**Positive**

- The Overview Network card, already fully scaffolded and shipping hardcoded zeros,
  now shows real live rates (~1 Hz) — zero new UI, zero schema-drift risk beyond the
  one new type.
- Validates the WS subscription transport on real hardware in a narrowly scoped
  balloon before broader rollout (metrics, docker stats).
- The `subscriptionStream` substrate is in place for subsequent consumers (metrics
  poll→subscription is the next highest-value candidate per ADR-0026).
- No new dependency; reuses the ADR-0041 self-signed pin path without modification.
- Apollo WS auto-reconnects and re-sends `connectionPayload`; brief drops hold the
  last `Content` value (analogous to ADR-0017's transient tolerance).

**Negative / trade-offs**

- Two transports in production for the first time: HTTP polling (all other domains)
  + WS subscription (network). More moving parts; more reconnect/lifecycle surface.
- No poll fallback for network: a sustained WS failure shows "unavailable" rather
  than a stale rate. Acceptable here because stale byte rates are misleading, but
  users lose the card during a WS outage.
- The `graphql-smoke` skill (`scripts/graphql-smoke`) is HTTP-only — it cannot
  exercise the WS subscription path. WS delivery is validated via the read-only
  python `websockets` client session (live-verified 2026-05-29) and the
  maintainer's on-device acceptance gate (ADR-0027 Tier 3). CI unit tests cover
  the pure logic seams (`selectThroughput`, `formatBytesPerSec`,
  `subscriptionStreamForTest`) but not the live WS transport.
- The self-signed TLS integration risk: wiring the custom `sslSocketFactory` into
  Apollo's WS engine is the fiddliest part. Mitigated by reusing the existing HTTP
  trust path exactly — the HTTP poll surfaces the cert prompt (ADR-0041) first; the
  WS only ever connects with an already-pinned cert.

**Trigger to revisit**

- If the Network card does not show live rates on the maintainer's real server
  after on-device acceptance of `0.1.40-beta1`: stop, diagnose, do not promote to
  stable. The WS transport or TLS wiring needs correction.
- If the WS substrate proves reliable for network, proceed with metrics
  (CPU/Mem/Temp) as the next consumer — highest-value poll to replace (2 s cadence).
- If `arraySubscription` is fixed server-side (it emits `Cannot return null for
  non-nullable field` per the ADR-0026 amendment), re-evaluate array subscription.

**Explicitly out of scope for this beta**

- Metrics subscriptions (`systemMetricsCpu`, `systemMetricsMemory`,
  `systemMetricsTemperature`) — still polling at 2 s.
- `dockerContainerStats` — still no client-side consumer.
- `arraySubscription` — server-side broken (ADR-0026 amendment); stays polling.
- `notificationsWarningsAndAlerts` — server-side FS watcher gated (ADR-0026);
  stays polling.
- Per-interface UI, historical persistence, the Network-interfaces settings screen
  — all unchanged.
- Mutations over WS — none; maintainer-only mutation convention unaffected.

## Alternatives considered

**Approach 1 — network-only minimal (no reusable substrate).** Inline the WS
client and mapping directly in the repository without a generic `subscriptionStream`.
Lower initial complexity; the substrate would need to be extracted anyway when
metrics follow. The maintainer chose Approach 2 (reusable substrate) explicitly
during brainstorming — the marginal cost of the generic layer is low once the first
consumer exists.

**Poll fallback for network.** ADR-0026 mandated fallback for all domains. Network
throughput has no query path — there is no `Query` field for byte rates. A fallback
would require either fabricating zero (misleading) or omitting the card (same as
the current hardcoded-zero state). No fallback is the honest choice: the card shows
"unavailable" on WS failure, which is accurate.

**Metrics-first instead of network-first.** Metrics (CPU/Mem/Temp) are the
highest-value poll to replace and were pre-validated by the ADR-0026 amendment.
However, the agent can now test network delivery directly (live-verified), and the
network field is new — testing it early surfaces any upstream reliability issues
before committing the metrics poll path. Network is the better balloon target.

## References

- ADR-0026 — GraphQL subscriptions hybrid (this ADR supersedes for network
  throughput; metrics/docker/array domains still pending).
- ADR-0017 — Domain-split lifecycle polling; `domainStream` pattern this mirrors.
- ADR-0016 — Separate long-running Apollo client; factory ownership model.
- ADR-0041 — Host+SSL entry and self-signed TOFU trust; the pin this ADR reuses.
- ADR-0027 — Agent autonomy & access model (Tier-3 on-device acceptance gate).
- ADR-0006 — Beta-first shipping policy (mandates beta before stable for new
  transport paths).
- Live verification session: `wss://192.168.11.2/graphql`, 2026-05-29, python
  `websockets` client, `graphql-transport-ws` subprotocol, `x-api-key` auth —
  `systemMetricsNetwork` delivering `rxBytesPerSec`/`txBytesPerSec` at ~1 Hz.
