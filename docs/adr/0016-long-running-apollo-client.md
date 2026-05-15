# ADR-0016: Separate Apollo client for long-running mutations

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: data, ui, performance

## Context

The `ApolloClientFactory` (`app/src/main/kotlin/net/unraidcontrol/app/data/api/ApolloClientFactory.kt:27-42`) builds one `ApolloClient` per Unraid server. Its underlying `OkHttpClient` is configured with `connectTimeout = 8 s` and `readTimeout/writeTimeout = 15 s`. Those settings were chosen so the snapshot polling loop (`UnraidRepository.snapshotStream`, 2 s interval) can quickly distinguish a healthy server from an unreachable one — 15 s of dead silence is the threshold at which the UI flips into the offline-error state.

The planned Phase B feature — per-container "Update" action wired to the GraphQL `updateContainer(id)` mutation — breaks that timeout assumption fundamentally. The server-side flow behind a single `updateContainer` is: pull the new image, stop the current container, recreate it from the template, start the new one. **Realistic completion time is 30 s to several minutes** depending on image size, registry speed, and how many layers changed. A 15 s read timeout would kill the HTTP connection mid-pull every single time.

Three constraints make this awkward:

1. **Polling must stay snappy.** Snapshot polling at 2 s with a 15 s read timeout means up to ~17 s for offline detection — already noticeably slow. Globally raising the timeout to, say, 600 s makes offline detection take 10 minutes. Unusable.

2. **UI mustn't block.** Per Phase B's product decision (user-chosen UX in the plan: "Updating…-Pill, UI bleibt bedienbar"), the user must be free to navigate, open other containers, scroll, switch tabs, etc. while an update is in flight. A `suspend` mutation that awaits a multi-minute response on the UI's `ViewModelScope` still allows that *if* it doesn't block the UI thread (Apollo + OkHttp don't), but it does pin a coroutine open for the duration — and that coroutine needs an HTTP client that won't cut its own connection.

3. **Bulk update will magnify the issue.** Phase C's `updateAllContainers` mutation can legitimately take 10+ minutes across many containers. Without a timeout strategy that scales, bulk update is a non-starter.

## Decision

**Add a second per-server Apollo client tuned for long-running mutations, and route exactly the update mutations through it.** All polling, all info queries, all short-lived mutations (start/stop/pause/unpause/restart) continue to use the existing client unchanged.

Concretely:

- `ApolloClientFactory` gains a method `buildLongRunningClient(active: ActiveServer): ApolloClient` with the same auth/headers/base-URL setup as `buildClient`, differing only in OkHttp timeouts: `connectTimeout = 8 s`, `readTimeout = 10 min`, `writeTimeout = 10 min`. Connect timeout stays short — if the server is unreachable, we want to know immediately even for mutations.
- `UnraidRepository` caches both clients per server (the existing per-server client cache extends to hold a `Pair<ApolloClient, ApolloClient>` or similar). A new private `activeLongRunningClient(): ApolloClient?` mirrors the existing `activeClient()` helper.
- The three new repository methods — `updateContainer(id)`, `updateContainers(ids)`, `updateAllContainers()` — use `activeLongRunningClient()`. They are still `suspend` and still cancellable through `viewModelScope`.
- The ViewModel tracks an `updatingContainerIds: MutableStateFlow<Set<String>>` and surfaces it through `MainUi`. The update action mark-up happens in a `try { … } finally { … }` so the set is drained whether the mutation succeeds, fails, or the scope is cancelled. The snapshot poll (2 s) reveals the post-update state via `isUpdateAvailable: false` and `state: RUNNING` and the UI naturally returns to its idle look.

The 10-minute ceiling is conservative but pragmatic. Empirically Unraid image pulls top out at a few minutes for very large containers on slow connections; 600 s gives generous headroom without pretending the operation can run all day. If a real-world update hits 10 minutes, that's a separate problem (server overloaded, network broken) and timing it out at that point is correct behaviour.

## Consequences

**Positive**

- Long-running update mutations work without affecting offline-detection latency for polling. The two concerns are decoupled at the HTTP-client level.
- UI stays interactive during updates — the user can open another container's detail sheet, switch tabs, scroll, or even start a second update — all while the first mutation is still in flight on its own coroutine.
- The pattern generalises. Phase C's `updateAllContainers` will use the same long-running client unchanged. Future operations with similar timing profiles (parity-check trigger? large file-system operations? bulk container removal?) drop into the same slot.
- Failure semantics stay simple: if the long mutation throws (timeout, network drop, server error), the `finally` removes the container from the updating set, the user sees normal state again, can retry.

**Negative / trade-offs**

- Two clients per server doubles the per-server state in `ApolloClientFactory` and `UnraidRepository`. Modest; the clients share their underlying `OkHttpClient` connection pool implicitly through OkHttp's defaults, so resource cost is mostly bookkeeping.
- Mutation interruption when the app process dies (Android kills the activity, OOM, user force-stops): the HTTP connection drops mid-pull. Server-side, unraid-api likely continues the docker pull regardless (the operation was initiated server-side), so the *operation* finishes — but the app doesn't observe completion and the user's `updatingContainerIds` was never drained. Next snapshot poll on next app launch shows the correct end state, but the in-flight UI feedback is lost. Acceptable for v1.
- The 10-minute read timeout creates a worst-case scenario where a genuinely-stuck mutation appears "running" in the UI for ten minutes before timing out. Acceptable trade-off vs. cutting legitimate slow pulls; future work could add an explicit cancel mechanism if there's user demand.
- Two clients = two sets of HTTP logging output in debug. Minor.

**Trigger to revisit**

- If unraid-api adds a subscription / async-job API for long operations (mutation returns immediately with a job ID, subscription streams progress), replace the long-running-client polling pattern entirely. The long-running client becomes obsolete.
- If we encounter a single mutation that legitimately exceeds 10 minutes in normal operation, revisit the timeout ceiling (or, better, switch to the subscription pattern above).

## Alternatives considered

**Raise the global read timeout to 10 minutes.** Single-client simplicity. Rejected: kills offline-detection latency for the polling loop. A user with a dead Unraid server would wait 10 min to see the offline state instead of ~15 s today.

**Per-call timeout override via OkHttp interceptor.** Conceptually cleaner — one client, the interceptor inspects the operation name and bumps the read timeout for known long-running mutations. Rejected: more code than two clients, more places for the timeout config to drift out of sync with the list of long-running operations, and harder to reason about (the timeout for a given call depends on a side-effectful interceptor lookup).

**Block the UI with a modal spinner during the update.** Simplest implementation: the `suspend` call just awaits the response, the Detail-Sheet shows a non-dismissible spinner. Rejected explicitly by Phase B's product UX decision — locking the UI for a 3-minute update is a frustrating user experience, and bulk update (Phase C) would make this 10-minute-modal territory. Non-starter.

**Fire-and-forget without a long-running client (just kick the mutation and ignore the response).** Tempting — the server keeps pulling regardless of the HTTP connection state, and snapshot polling reveals the end state anyway. Rejected: we lose the ability to surface mutation-level errors (auth failure, invalid container ID, server-side update error). The mutation response includes those. Without consuming the response we'd silently fail.

## References

- The Apollo client factory the new method extends: `app/src/main/kotlin/net/unraidcontrol/app/data/api/ApolloClientFactory.kt:27-42`.
- The repository mutation pattern this builds on: `UnraidRepository.kt:138-146` (existing `startContainer`/`stopContainer`/etc.).
- Phase B of the Docker-update roadmap (per-container Update action), documented in the local planning file.
- Related: ADR-0017 *proposed* (domain-split queries + lifecycle polling) — independent but synergistic. Once Phase D ships, the Docker-domain stream can transiently bump to a 1 s poll rate during an active update to give the user near-instant "Updating → Up to date" feedback, without affecting other domains.
