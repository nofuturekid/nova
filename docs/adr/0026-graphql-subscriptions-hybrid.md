# ADR-0026: GraphQL subscriptions for select domains (hybrid with polling)

- **Status**: Deprecated — piloted (E0+E1) then reverted, see Outcome
- **Date**: 2026-05-16
- **Tags**: data, performance, resilience, api

## Outcome (E1 pilot, reverted)

E0 (WS transport) + E1 (notifications subscription) shipped as
0.1.29-beta9…beta12 and were tested against a real current Unraid
server. Findings:

- **Transport works.** The `graphql-ws` WSS connection to
  `wss://<host>/graphql` established and authenticated with the same
  `x-api-key` (no `connection_init` timeout / close / auth error ever
  surfaced on-device).
- **No subscribe-time snapshot — for any notification subscription.**
  Verified in `unraid/api` source: the resolvers use a bare
  `pubsub.asyncIterableIterator` (no replay) and the FS watcher runs
  `ignoreInitial: true`. A subscriber gets *only* events published
  after it subscribes. The query poll as seed + reconciliation is
  therefore mandatory, not optional — which validated the seed+overlay
  design but also removed most of the subscription's value.
- **Notification events did not deliver on the real server.** A new
  ALERT created via the legacy `notify` script was picked up by the
  60 s query poll but never by `notificationsWarningsAndAlerts` (pill
  stayed "60s poll", no WS error). All three notification
  subscriptions share the same chokidar `add` trigger; the most
  likely cause is the api's FS watcher not seeing the legacy script's
  writes (inotify not propagating in the api's environment, or a
  notify-path mismatch) — **server-side, not client-fixable**, and it
  would affect every notification subscription equally.
- **Net value too low to carry the complexity.** Notifications change
  rarely and the 60 s poll already reconciles them; with no
  subscribe-snapshot and a server-dependent FS-watcher trigger, the
  WS path added a second transport, schema-subset growth, and
  reconnect/lifecycle handling for ~0 reliable benefit on real
  hardware.

**Decision: reverted in 0.1.29-beta13.** All E0/E1 code (WS transport,
`subscriptionStream`, the subscription schema/operation/mapper, the
pilot transport badge) is removed; `notificationsStream` is back to the
ADR-0017 poll. Polling stays the only data path. E2–E4 are **not**
pursued: the highest-value target (system metrics) uses a different
(interval) publisher that *might* deliver, but the pilot showed the
practical payoff is gated by per-server/per-domain server-side
behaviour we can't control or test broadly, so the complexity isn't
justified. This ADR is kept as the record of *why* subscriptions were
evaluated and dropped, so it isn't re-litigated.

**Trigger to revisit:** if Lime Technology documents/guarantees
subscription delivery (with a subscribe-time snapshot) for a
high-value domain, or the API gains server-push that doesn't depend
on the FS watcher, re-evaluate from this ADR's Outcome — don't restart
from the original premise.

## Context

ADR-0017 split the data layer into five lifecycle-gated polling streams.
Its own *trigger to revisit* states: "If we introduce a GraphQL
subscription/WebSocket pathway for any of these domains, the per-domain
polling becomes redundant for that domain — replace the polling flow with
a subscription flow, keep the gating layer unchanged."

An earlier read (Phase E in the backlog) concluded "the Unraid API offers
no subscriptions" and shelved the idea. That conclusion was **wrong**: it
was drawn from our hand-written `schema.graphqls` *subset*, which only
mirrors `Query` + `Mutation`. The authoritative upstream schema
(`unraid/api` `generated-schema.graphql`) has a full `type Subscription`.

A read-only spike against the `unraid/api` source established the facts:

- **Transport is served.** NestJS 11 + `@nestjs/apollo` + `graphql-ws@6`,
  configured (`graph.module.ts`) on the **same `/graphql` path** as HTTP,
  subprotocol `graphql-transport-ws`. Production binds a Unix socket
  behind the WebGUI nginx; the WS upgrade rides the same vhost the app
  already uses for HTTPS queries — i.e. `wss://<host>/graphql`, no new
  endpoint to discover.
- **Auth reuses the existing API key.** The auth guard merges
  `connectionParams` into request headers; the API-key strategy reads
  lowercase `x-api-key`. A subscription authenticates with the connection-
  init payload `{ "x-api-key": <same key> }` — same credential, same
  code path as queries.
- **Apollo Kotlin 5** natively supports this exact protocol
  (`WebSocketNetworkTransport` + `GraphQLWsProtocol`).

Mapping the full `type Subscription` against our six polling domains:

| Domain (today) | Poll | Subscription | Fit |
|---|---|---|---|
| Metrics (CPU/RAM) | **2 s** | `systemMetricsCpu` / `systemMetricsMemory` / `systemMetricsTemperature` | Strong — kills the most aggressive poll |
| Notifications | 60 s | `notificationsOverview` / `notificationAdded` | Strong — inherently event-driven; UI already built |
| Array | 5 s | `arraySubscription` (+ `parityHistorySubscription`) | Good — parity/disk events |
| Docker container **stats** (CPU/Mem per container) | *(none today)* | `dockerContainerStats` | Net-new capability; firehose, no `id` arg |
| Docker container **list/state** | 2 s | *none* (`docker.containers` has no subscription) | Stays polling |
| VMs | 3 s | *none* | Stays polling |
| Server info / config | 60 s | *(near-static; not worthwhile)* | Stays polling |

Two caveats on the usable subscriptions: `dockerContainerStats` has no
`id` argument — one stream emits every container's stats (~1 msg/container
/sec); the client must accumulate into a map keyed by the `PrefixedID`
(same scalar form the `docker` query returns), exactly as the upstream
web UI does. And the notifications subscription fields were a recent
schema addition — older Unraid API versions may not expose `type
Subscription` or specific fields at all.

## Decision

**Adopt GraphQL subscriptions as a *hybrid augmentation* of the polling
layer for the domains that have one, with mandatory automatic fallback to
the existing poll loop. Polling (ADR-0017) is amended, not retired —
Docker container list/state, VMs and server info have no subscription and
remain polled.**

Concretely:

- Add a `WebSocketNetworkTransport` (`GraphQLWsProtocol`, `wss://<host>
  /graphql`, connection payload `{ x-api-key }`) to the existing Apollo
  client, coexisting with the HTTP transport. One client, both transports.
- A new `subscriptionStream` repository helper mirrors `domainStream`
  (ADR-0017): same `DomainState` shape, same replay-1 cache, **app-visible
  gated identically** (`gatedStream(_appVisible, …)`) so a backgrounded
  app holds no socket. On WS error / close / "field not found" it
  **falls back to the existing per-domain poll loop** for that domain.
  Fallback is a hard requirement, not an optimisation: it is the only
  thing that keeps older API versions working.
- Roll out in phases, each its own beta + device verification, under this
  one umbrella ADR:
  - **E0 — transport foundation** (infra, no UX): WS transport, auth
    payload, `subscriptionStream` helper with poll fallback.
  - **E1 — notifications (pilot, go/no-go gate):** smallest payload, UI
    already exists, immediately visible benefit. Validates version-compat
    + nginx WS-upgrade against a real server. If fallback engages, the
    safety net is proven before investing in E2–E4.
  - **E2 — system metrics:** retire the 2 s metrics poll → push.
  - **E3 — array** (+ parity history).
  - **E4 — container stats:** `dockerContainerStats` firehose, accumulate
    by `PrefixedID`, wire `Container.cpu`/`memMb` (today hardcoded 0);
    the container *list* stays polled — hybrid card.
- Out of scope (no subscription exists): VM state, Docker container
  list/state, server info — remain ADR-0017 polling indefinitely.

The hand-written `schema.graphqls` subset grows a `type Subscription`
plus the concrete payload types, each verified against the upstream
`generated-schema.graphql` before use (same discipline as the
notifications query).

## Consequences

**Positive**

- The most aggressive poll (metrics, 2 s) and the least-timely one
  (notifications, 60 s) both become push: lower battery, instant alerts,
  smoother sparklines with no fixed-interval lag.
- Server-side cost drops: the stats/metrics streams are refcounted and
  only run while a subscriber is connected; gating already closes the
  socket on background.
- Container CPU%/Mem becomes possible at all — there is no query path for
  it; subscription is the only route.
- Builds on, rather than replaces, ADR-0017: the gating/cache/`DomainState`
  substrate is reused unchanged; the poll loops survive as the fallback.

**Negative / trade-offs**

- Permanent hybrid: two transports, reconnect/backoff with auth re-send,
  WS lifecycle on top of the existing poll lifecycle. More moving parts.
- The `schema.graphqls` subset grows and must track upstream subscription
  types — same drift risk as today, larger surface.
- `dockerContainerStats` is a firehose: client must accumulate by id and
  tolerate N msgs/sec; a single shared subscription, not one per card.
- WS-upgrade reliability varies by user setup (reverse proxies, Unraid
  Connect remote vs LAN). The pilot exists specifically to surface this.

**Trigger to revisit**

- If the notifications pilot (E1) cannot establish a WS session, or
  fallback proves unreliable, against a real current-version server:
  stop. Keep polling, mark this ADR `Deprecated`, document the blocker.
- If a future API version adds subscription fields for the domains that
  currently lack them (docker container list, vms), extend the same
  hybrid pattern to those domains.
- If maintaining the subscription schema subset becomes a recurring
  source of "cannot query field" breakage, reconsider schema
  introspection-on-connect (the alternative ADR-0017 deferred).

## Alternatives considered

**Stay polling-only (status quo / original Phase E verdict).** Zero new
infrastructure, but leaves the 2 s metrics poll and 60 s notification lag
on the table and makes per-container stats permanently impossible. The
original verdict rested on a factual error (subset vs. real schema);
once corrected, "do nothing" is no longer the low-risk default it
appeared to be.

**Replace polling wholesale with subscriptions.** Not possible: Docker
container list/state, VMs and server info have no subscription field.
A full switch would regress exactly the domains that drive the app's
core "start/stop a container, check a VM" use case.

**Container stats only (the isolated original #2).** Implement just
`dockerContainerStats`, skip the umbrella. Delivers the narrowest value
for almost the same infrastructure cost (WS transport, auth, schema
subset) — once that scaffolding exists, notifications and metrics are
cheap incremental wins. Doing only #2 leaves the biggest wins (metrics
push, real-time alerts) unbuilt while still paying the full setup price.

**Schema introspection on connect.** Build subscription documents
dynamically from `__schema` so version drift can't cause "cannot query
field". Same elegance/complexity trade-off ADR-0017 already weighed and
deferred; the per-domain fallback achieves the resilience goal without
sacrificing Apollo compile-time types. Revisit only if drift breakage
recurs.

## References

- Amends ADR-0017 (domain-split lifecycle polling) — actions its
  subscription *trigger to revisit*; ADR-0017 stays `Accepted`.
- Related: ADR-0016 (separate long-running Apollo client), ADR-0024
  (API-key storage — the same key authenticates the WS connection).
- Upstream evidence (`unraid/api`, branch `main`):
  `api/src/unraid-api/graph/graph.module.ts` (subscriptions config),
  `api/src/unraid-api/auth/authentication.guard.ts` +
  `header.strategy.ts` (WS auth), `.../resolvers/docker/docker.resolver.ts`
  + `docker-stats.service.ts` (firehose), `generated-schema.graphql`
  (`type Subscription`), `web/src/composables/useDockerContainerStats.ts`
  (accumulate-by-id pattern).
