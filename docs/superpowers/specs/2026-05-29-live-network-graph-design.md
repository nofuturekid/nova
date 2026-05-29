# Live Network Graph — Design Spec

- **Date**: 2026-05-29
- **Status**: Approved (brainstorm) — pending implementation plan
- **Feature**: Feed live network throughput into the existing Overview "Network" card via a GraphQL subscription.
- **Framing**: A scoped **test balloon** that re-validates the WebSocket subscription transport on real hardware before metrics/docker subscriptions follow. Ships beta-first.

## 1. Background & motivation

The Overview dashboard already renders a **Network `StatCard`** (same component as
CPU/Memory) with a sparkline series `netSeries` — but every input is hardcoded
(`OverviewTab.kt`):

- `value = "%.1f Mbps".format(0.0)`
- `sub = "↓ 0.0 · ↑ 0.0 Mbps"`
- `netSeries` appends `0f` each tick (line 135), initialised to `List(40){0f}` (line 127)

So the card is fully scaffolded and shipping today showing zeros. The feature is to
**give it real data**, not to build new UI.

The data source is the Unraid GraphQL **subscription** `systemMetricsNetwork`, which
pushes per-interface throughput ~1×/sec over a WebSocket. This was **live-verified on
2026-05-29** against the real server (`wss://192.168.11.2/graphql`, subprotocol
`graphql-transport-ws`, auth via `x-api-key`): `NetworkInterfaceUtilization` delivers
`iface`, `rxBytes`, `txBytes`, `rxBytesPerSec`, `txBytesPerSec` for `lo`/`eth0`–`eth3`,
with live rates (e.g. eth0 ≈ 3.3 KB/s rx, 2.8 KB/s tx).

### Prior art — ADR-0026

ADR-0026 piloted a *generic* subscription substrate (E0 WS transport + E1 notifications)
in 0.1.29-beta9…beta13, then **reverted** it. The deciding factor was **iterability**: each
hypothesis cost a multi-beta user-relay loop without a test environment the agent could
drive directly (ADR-0027). Its 2026-05-17 amendment declared **network throughput
"impossible regardless of transport"** because the API exposed no such field.

Two things changed that make this revisit valid:

1. **The API added network throughput.** `systemMetricsNetwork` now exists and delivers
   (live-verified 2026-05-29) — the 2026-05-17 "impossible" claim is stale.
2. **The agent can now drive the real server directly** (read-only WS smoke via a local
   python client), collapsing the iteration cost that caused the revert.

This is therefore an explicit ADR-0026 revisit trigger ("the API gains server-push").

## 2. Decisions locked during brainstorming

| Decision | Choice | Rationale |
|---|---|---|
| Placement | **Existing Overview Network card** (no new screen/card) | The card already exists; only wiring is missing. |
| Interface scope | **Management interface only** | Resolve via `GetNetworkInterfaces.primaryNetwork.name`; avoids bond/bridge double-counting. |
| Units | **Adaptive bytes** (B/s · KB/s · MB/s · GB/s) | Honest at low rates; "Mbps" rounds 3.3 KB/s to "0.0". |
| Architecture | **Approach 2 — reusable subscription substrate**, network as first consumer | Maintainer's choice (over the minimal-only recommendation). |
| Scope this beta | **Substrate + network consumer only** | Metrics/docker/array stay polling; follow in later betas. Keeps the balloon small. |
| Release | **Beta-first** (`0.1.40-beta1`) | New transport touching how the app talks to the server ⇒ ADR-0006 mandates beta-first. |

## 3. Architecture & data flow

### 3.1 WS transport — `ApolloClientFactory.buildWs(baseUrl, apiKey, trust)`

A WebSocket-capable `ApolloClient`, alongside the existing HTTP clients, cached per
`(endpoint, key, trust)` exactly like `build`/`buildLongRunning`:

- `WebSocketNetworkTransport` → `wss://<host>/graphql`, `GraphQLWsProtocol`,
  `connectionPayload = { "x-api-key": apiKey }` (same credential as HTTP).
- **Reuses the existing self-signed TLS plumbing.** The WS engine is fed an OkHttpClient
  built with the same `TlsTrust`/pinning logic as the private `buildOn(...)`
  (`LocalPinningTrustManager`, `sslSocketFactory`, hostname bypass for pinned self-signed —
  ADR-0041). No new trust path; the WS reuses the pin the HTTP poll already established.
- Apollo 5's `apollo-runtime` already provides this transport (OkHttp-backed) — **no new
  dependency**.

> **Primary integration risk.** Wiring the custom `sslSocketFactory` into Apollo's WS
> engine for the self-signed local case is the fiddliest part. If the cert is not yet
> trusted, the existing HTTP poll surfaces the cert prompt (ADR-0041) first; the WS only
> ever connects with an already-pinned cert. The implementation plan must verify the WS
> handshake honours the pin on-device.

### 3.2 Generic `subscriptionStream<T>` — `UnraidRepository`

Mirrors the existing `domainStream`:

- Keyed on `servers.activeWithKey` → `distinctUntilChanged` → `flatMapLatest`.
- Emits `DomainState`: `NoServer` when no active server; `Error` when the key is blank /
  undecryptable (same messages as `domainStream`); otherwise collects
  `buildWs(url, key, trust).subscription(op).toFlow()` and maps each response to
  `DomainState.Content(map(data)).withBaseUrl(url)`.
- **Cold** ⇒ the WS socket opens when the flow is collected and closes when collection is
  cancelled (gate flip / leaving Overview / app background).
- **Reconnect:** Apollo's WS transport auto-reconnects and re-sends `connectionPayload`;
  brief drops keep re-showing the last `Content` (analogous to `pollDomain`'s transient
  tolerance). A sustained failure surfaces `Error`.
- **Optional fallback-poll hook** preserved from the ADR-0026 contract — **network passes
  none** (no query path for throughput exists), so on sustained WS failure it yields
  `Error` → the card shows "unavailable".

### 3.3 Consumer — `UnraidRepository.networkThroughputStream()`

- Resolves the primary NIC name once per connection via the existing
  `GetNetworkInterfaces` query (`primaryNetwork.name`).
- Subscribes `SystemMetricsNetwork`, maps each frame to the matching interface's
  `rxBytesPerSec`/`txBytesPerSec` → `NetworkThroughput(rx, tx)`.
- **Fallback if** `primaryNetwork.name` is null or not present in the frame: the first
  non-`lo` interface that is reporting.

### 3.4 UI wiring

- **`MainViewModel`**: `val networkThroughputState = gatedStream(overviewOnly(), unraid.networkThroughputStream())`
  — gated **identically to `metricsState`**, so the WS socket lives only while Overview is
  the foreground tab. `gatedStream` already provides `WhileSubscribed(5s)` caching and the
  `resetIfForeignServer` server-identity guard; the subscription `Content` is tagged with
  `serverBaseUrl` via `withBaseUrl` so the guard works unchanged.
- **`OverviewTab`** (plumbed through `MainScreen` like the other domain states):
  - Network card `value` = `formatBytesPerSec(rx + tx)`; `sub` = `"↓ ${formatBytesPerSec(rx)} · ↑ ${formatBytesPerSec(tx)}"`.
  - `netSeries` advanced by a **new `LaunchedEffect(networkThroughput)`** on each WS sample
    (~1 Hz) with value `rx + tx`. CPU/RAM series keep keying on `metrics`. `max = null`
    already auto-scales the sparkline.
  - On "unavailable": card shows `—`, sub "live unavailable", and the sparkline holds its
    last value (no synthetic 0 spike, no error storm, no crash).

### 3.5 Lifecycle summary

```
Overview visible & app foreground  ──▶ gate open ──▶ collect networkThroughputStream
   ──▶ buildWs() opens WSS ──▶ SystemMetricsNetwork frames (~1 Hz) ──▶ card + sparkline
Leave Overview / background app    ──▶ gate closed ──▶ flow cancelled ──▶ WSS closed
```

## 4. Schema, model, operation

**`schema.graphqls`** — add (each verified against upstream `generated-schema.graphql`
before use; fields already live-verified):

```graphql
type Subscription { systemMetricsNetwork: NetworkUtilization! }
type NetworkUtilization { id: PrefixedID! interfaces: [NetworkInterfaceUtilization!]! }
type NetworkInterfaceUtilization { iface: String rxBytesPerSec: Float txBytesPerSec: Float }
```

> Exact nullability/wrapping must be copied from upstream, not guessed — the
> `GetNotificationList` trap (vendored subset diverged from live) is the cautionary
> precedent.

**New `subscriptions.graphql`** (read-only; `mutations.graphql` untouched →
maintainer-only mutation convention preserved):

```graphql
subscription SystemMetricsNetwork {
  systemMetricsNetwork { interfaces { iface rxBytesPerSec txBytesPerSec } }
}
```

**`Models.kt`**: `data class NetworkThroughput(val rxBytesPerSec: Double, val txBytesPerSec: Double)`.

**Mapper** (`GraphQlMapper.kt`): `toNetworkThroughput(primaryIface: String?): NetworkThroughput`.

## 5. Formatting

Pure helper `formatBytesPerSec(bytesPerSec: Double): String`:

- Adaptive: `B/s` → `KB/s` → `MB/s` → `GB/s`, **decimal (1000-based)**, 1 decimal place.
- Examples: `0 → "0 B/s"`, `3300 → "3.3 KB/s"`, `12_000_000 → "12.0 MB/s"`.

## 6. Testing (Rule 9 — encode intent, not just behaviour)

Pure unit tests (no live WS needed):

- `formatBytesPerSec`: unit boundaries (999→B, 1000→KB, …), rounding, zero.
- `toNetworkThroughput`: primary-iface match; fallback to first non-`lo` when name
  null/absent; `lo` never chosen as primary fallback.
- `DomainState` mapping for the subscription path (Content/Error transitions).
- `subscriptionStreamForTest` internal seam (mirrors `pollDomainForTest`) — drives the
  mapping + fallback decision with a caller-scripted flow so the *production* logic is
  pinned, not a copy.

Live WS delivery is **the maintainer's on-device acceptance gate** — the whole point of the
balloon. Already proven to deliver via the read-only WS smoke (python `websockets` client).
CI remains the build/test authority (no local Android toolchain).

## 7. ADR

New **ADR-0042 "GraphQL subscription transport — network throughput pilot"**:

- Supersedes ADR-0026's deprecation for this scoped slice.
- Records: the live evidence (rates deliver, ~1 Hz, near-instant first event); the
  **no-fallback** decision for network; the `overviewOnly()` lifecycle gating; the
  self-signed-pin reuse; that the `graphql-smoke` skill is HTTP-only and cannot cover
  subscriptions (WS validation = python client / on-device).
- Explicitly keeps **metrics, docker stats, array** out of scope for this beta.
- ADR-0026 status updated → "Superseded by ADR-0042 (network pilot); other domains still
  pending."

## 8. Release

- **Beta-first** (ADR-0006): ships as `0.1.40-beta1`, `versionCode` 107 → 108. Never
  direct-to-stable; stable promotion stays the maintainer's on-device gate (ADR-0027).
- **CHANGELOG** (ADR-0031, plain language, under `## [Unreleased]` → renamed by the
  version-bump PR): *"The Network card on the dashboard now shows live upload/download
  speed in real time."*

## 9. Out of scope

- Metrics (CPU/Mem/Temp), `dockerContainerStats`, `arraySubscription` — later betas /
  separate specs.
- Per-interface UI, historical persistence, the static Network-interfaces screen
  (unchanged).
- Mutations over WS (none; maintainer-only convention unaffected).

## 10. Execution (CLAUDE.md Rule 14)

Once this spec is approved, the entire execution tail is delegated to sub-agents — code
fan-out (transport, substrate, consumer, model/mapper, formatter, UI wiring, tests),
schema, ADR-0042, version bump, CHANGELOG, branch/commit/PR, CI watch, beta tag — with the
`release-engineer` subagent owning the release tail. The maintainer's stable-promotion gate
is the only human-only step. Main thread stays overwatch/verify.
