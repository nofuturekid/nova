# ADR-0028: Cache Apollo clients per (server, variant) in the factory

- **Status**: Proposed
- **Date**: 2026-05-17
- **Tags**: data, performance, api

## Context

`ApolloClientFactory.build()` / `buildLongRunning()` constructed a brand-new
`ApolloClient` — each wrapping a fresh `OkHttpClient` derived via
`base.newBuilder()…build()` — on **every call**. The call sites make that a
hot path:

- `UnraidRepository.domainStream` builds one client per active-server
  resolution, ×6 domain streams.
- `refreshAll()` builds one on every pull-to-refresh.
- Every mutation helper (`activeClient()` / `activeLongRunningClient()` →
  `buildClient` → `build`), `containerLogs()`, and `testConnection()` build
  one **per invocation**.

None of these were ever `close()`d. Each derived `OkHttpClient` shares the
base connection pool/dispatcher (so the network-resource cost is bounded),
but the `ApolloClient` objects themselves — interceptor chain, internal
coroutine/scope machinery, buffers — accumulated for the process lifetime.
On a server with frequent pull-to-refresh and tab switching this is a
steady, unbounded `ApolloClient` leak.

Notably **ADR-0016 already described this cache as existing**: *"`UnraidRepository`
caches both clients per server (the existing per-server client cache extends
to hold a `Pair<ApolloClient, ApolloClient>`…)."* The implementation diverged
from that stated design — there was no cache. This ADR records the decision
and brings the code in line with the lifecycle ADR-0016 assumed.

A caller-side `try/finally { client.close() }` was considered and rejected
(see Alternatives): it only patches two of the many leak sites and fights the
shared-client direction.

## Decision

**The `@Singleton ApolloClientFactory` owns Apollo client lifecycle and
memoises one client per `(variant, endpoint, apiKey)` key.**

- `build()` / `buildLongRunning()` delegate to a private `cached(...)` that
  does `ConcurrentHashMap.computeIfAbsent(key) { buildOn(...) }`.
  `computeIfAbsent` is atomic on `ConcurrentHashMap`, so concurrent domain
  streams cannot race two clients into existence for the same key.
- Key = `"$variant|$endpoint|$apiKey"` where `variant ∈ {short, long}` and
  `endpoint = baseUrl.trimEnd('/') + "/graphql"`.
- **Callers MUST NOT call `close()`** on a returned client — it is shared.
  This convention is documented on the `clients` field.
- No call-site changes: every existing leak site (`domainStream`,
  `refreshAll`, mutations, `containerLogs`, `testConnection`) is fixed for
  free because they all route through the factory.

Clients live for the app's lifetime, bounded by the number of distinct
server configs actually used — in practice 1–2 servers × 2 variants ≈ 2–4
clients total.

## Consequences

**Positive**

- The `ApolloClient` leak is eliminated at one chokepoint; no per-call-site
  cleanup discipline to maintain.
- Connection reuse improves: the cached client's OkHttp pool persists across
  polls instead of starting cold each call.
- Code matches the lifecycle ADR-0016 already assumed.

**Negative / trade-offs**

- An API-key edit for an existing server leaves the old `(…|oldKey)` entry
  in the map until process death (a stale, never-reused client). Bounded by
  the number of key edits — negligible, and still vastly better than
  per-call creation. Not evicted, to keep the cache lock-free and trivial.
- Clients are never explicitly closed. Acceptable: the factory is
  `@Singleton`, the set is small and bounded, and the process owns it.
- A shared client means a misbehaving caller that closes it would break all
  others — mitigated by the documented "MUST NOT close" contract, not by the
  type system.

**Trigger to revisit**

- If server configs become numerous/dynamic enough that the unbounded-on-
  key-change map is a real footprint concern, add LRU eviction with
  `close()` on eviction.
- If a future need to *force* a fresh client appears (e.g. rotating auth
  mid-session), add an explicit `invalidate(baseUrl)` that closes and drops
  the entry.

## Alternatives considered

- **Caller-side `try/finally { client.close() }`** in `domainStream` and
  `refreshAll`. Patches only those two sites; leaves mutations,
  `containerLogs`, and `testConnection` leaking. Also pushes lifecycle
  responsibility onto every caller forever. Rejected — the factory is the
  natural owner.
- **One client per server held in `UnraidRepository`** (closer to ADR-0016's
  literal wording). Equivalent effect but spreads cache state into the
  repository and still needs a key→client map for the two variants. The
  factory is the cleaner home and keeps `UnraidRepository` about data, not
  client lifecycle.
- **Close clients on `flatMapLatest` cancellation only.** Doesn't help the
  one-shot helpers (mutations/logs/ping), which are the highest-frequency
  leak sites.

## References

- `app/src/main/kotlin/.../data/api/ApolloClientFactory.kt` — the cache.
- `app/src/main/kotlin/.../data/repository/UnraidRepository.kt` — all
  call sites now reuse cached clients.
- ADR-0016 — separate long-running client; already assumed this per-server
  cache and defines the two variants this keys on.
- ADR-0017 — domain-split polling; the per-poll `build()` call this fixes.
