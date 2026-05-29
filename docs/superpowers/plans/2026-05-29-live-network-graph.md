# Live Network Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Feed live network throughput (`systemMetricsNetwork` GraphQL subscription) into the existing Overview "Network" card via a reusable WebSocket subscription substrate, shipped beta-first.

**Architecture:** Add a WS-capable Apollo client (`buildWs`) that reuses the existing self-signed TLS pin (ADR-0041); a generic `subscriptionStream<T>` in `UnraidRepository` mirroring `domainStream` (cold, gated, `DomainState`); a `networkThroughputStream()` consumer that picks the management interface; wire it into `MainViewModel` (gated like `metricsState`) and the already-present Overview Network `StatCard`. Selection/format logic is factored into **pure seams** so it unit-tests without generated Apollo types.

**Tech Stack:** Kotlin, Jetpack Compose (M3), Apollo Kotlin 5.0.0 (`apollo-runtime` — WS built in, no new dep), Hilt, kotlinx-coroutines(-test), JUnit.

**Spec:** `docs/superpowers/specs/2026-05-29-live-network-graph-design.md`

---

## ⚠️ Verification model (READ FIRST)

**There is NO local Android toolchain. `./gradlew` cannot run on the host.** Per project convention CI is the only build/test authority (don't compile locally, don't send subagents to compile). Therefore:

- "Run the test" steps do **not** execute locally. Pure-logic tests are written test-first and **verified in CI** at the checkpoints (Task 8 / final push), via the `test` job (`./gradlew :app:testDirectDebugUnitTest`).
- Apollo codegen + compile + lint are verified by the `build` job (`assembleDirectDebug`/`assembleRelease` depend on `generateApolloSources`).
- Optional local pre-check: `./scripts/local-ci.sh` (pinned Docker container) runs codegen+lint+assemble (NOT unit tests). Use it to catch codegen/compile breakage before pushing if desired; it is not a substitute for the CI `test` job.
- **Commit frequently** (each task ends in a commit) so CI runs on each push and failures localise to one task.

All tasks land on branch `feat/live-network-graph` (already created; the spec commit `fa0d2ae` is its first commit).

---

## Task 1: GraphQL schema subset + subscription operation

**Files:**
- Modify: `app/src/main/graphql/io/github/nofuturekid/nova/schema.graphqls`
- Create: `app/src/main/graphql/io/github/nofuturekid/nova/subscriptions.graphql`

- [ ] **Step 1: Verify exact upstream shape.** Before editing, confirm the field nullability/wrapping against the authoritative upstream `unraid/api` `generated-schema.graphql` for `Subscription.systemMetricsNetwork`, `NetworkUtilization`, `NetworkInterfaceUtilization`. (Live-verified 2026-05-29 that the fields deliver; this step pins exact SDL, avoiding the `GetNotificationList` vendored-subset-divergence trap.)

- [ ] **Step 2: Add the `Subscription` type + payload types to `schema.graphqls`** (append near the other top-level types):

```graphql
type Subscription {
  systemMetricsNetwork: NetworkUtilization!
}

type NetworkUtilization {
  id: PrefixedID!
  interfaces: [NetworkInterfaceUtilization!]!
}

type NetworkInterfaceUtilization {
  iface: String
  rxBytesPerSec: Float
  txBytesPerSec: Float
}
```

- [ ] **Step 3: Create `subscriptions.graphql`** (read-only; do NOT touch `mutations.graphql` — mutations stay maintainer-only):

```graphql
subscription SystemMetricsNetwork {
  systemMetricsNetwork {
    interfaces {
      iface
      rxBytesPerSec
      txBytesPerSec
    }
  }
}
```

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/graphql/io/github/nofuturekid/nova/schema.graphqls \
        app/src/main/graphql/io/github/nofuturekid/nova/subscriptions.graphql
git commit -m "feat(graphql): add systemMetricsNetwork subscription schema + op"
```

CI/codegen verification happens at Task 8 (Apollo generates `SystemMetricsNetworkSubscription` from these files).

---

## Task 2: `NetworkThroughput` model + pure throughput-selection + byte formatter

This task is **pure Kotlin** (no Apollo types) → fully unit-testable in the `test` job. It contains the two pieces of logic that matter: which interface to read, and how to format a rate.

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/Models.kt`
- Create: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/util/ByteFormat.kt`
- Create: `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/NetworkThroughput.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/ui/util/ByteFormatTest.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/model/NetworkThroughputTest.kt`

- [ ] **Step 1: Write the failing formatter test** (`ByteFormatTest.kt`):

```kotlin
package io.github.nofuturekid.nova.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {
    @Test fun zero_is_bytes() = assertEquals("0 B/s", formatBytesPerSec(0.0))
    @Test fun just_under_kilo_is_bytes() = assertEquals("999 B/s", formatBytesPerSec(999.0))
    @Test fun kilo_boundary() = assertEquals("1.0 KB/s", formatBytesPerSec(1_000.0))
    @Test fun low_kb_is_honest() = assertEquals("3.3 KB/s", formatBytesPerSec(3_300.0))
    @Test fun megabytes() = assertEquals("12.0 MB/s", formatBytesPerSec(12_000_000.0))
    @Test fun gigabytes() = assertEquals("1.5 GB/s", formatBytesPerSec(1_500_000_000.0))
    @Test fun negative_clamped_to_zero() = assertEquals("0 B/s", formatBytesPerSec(-5.0))
}
```

- [ ] **Step 2: Implement the formatter** (`ByteFormat.kt`) — decimal (1000-based) to match networking convention, matching the codebase's locale-default `"%.1f".format(...)` style:

```kotlin
package io.github.nofuturekid.nova.ui.util

/** Adaptive decimal throughput formatter: B/s · KB/s · MB/s · GB/s. */
fun formatBytesPerSec(bytesPerSec: Double): String {
    val b = bytesPerSec.coerceAtLeast(0.0)
    return when {
        b < 1_000.0 -> "%.0f B/s".format(b)
        b < 1_000_000.0 -> "%.1f KB/s".format(b / 1_000.0)
        b < 1_000_000_000.0 -> "%.1f MB/s".format(b / 1_000_000.0)
        else -> "%.1f GB/s".format(b / 1_000_000_000.0)
    }
}
```

- [ ] **Step 3: Add the model** (`NetworkThroughput.kt`):

```kotlin
package io.github.nofuturekid.nova.data.model

/** Live per-second throughput for one interface. */
data class NetworkThroughput(
    val rxBytesPerSec: Double,
    val txBytesPerSec: Double,
) {
    companion object { val ZERO = NetworkThroughput(0.0, 0.0) }
}

/** Plain per-interface sample — the pure seam the Apollo mapper feeds, so
 *  interface-selection logic unit-tests without generated types. */
data class IfaceSample(
    val iface: String?,
    val rxBytesPerSec: Double,
    val txBytesPerSec: Double,
)

/**
 * Pick the throughput to display from a frame's interfaces.
 * Preference: the named management interface → first non-`lo` that is
 * actively moving bytes → first non-`lo` → ZERO. `lo` is never chosen.
 */
fun selectThroughput(samples: List<IfaceSample>, primaryIface: String?): NetworkThroughput {
    val chosen = samples.firstOrNull { it.iface == primaryIface && it.iface != "lo" }
        ?: samples.firstOrNull { it.iface != "lo" && (it.rxBytesPerSec + it.txBytesPerSec) > 0.0 }
        ?: samples.firstOrNull { it.iface != "lo" }
    return chosen?.let { NetworkThroughput(it.rxBytesPerSec, it.txBytesPerSec) } ?: NetworkThroughput.ZERO
}
```

> Note: `IfaceSample`/`selectThroughput` live in `Models.kt`-adjacent `NetworkThroughput.kt`; do NOT modify `Models.kt` unless a shared import requires it (the `Modify Models.kt` entry above is a fallback only).

- [ ] **Step 4: Write the failing selection test** (`NetworkThroughputTest.kt`):

```kotlin
package io.github.nofuturekid.nova.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkThroughputTest {
    private val lo = IfaceSample("lo", 5.0, 5.0)
    private val eth0 = IfaceSample("eth0", 3300.0, 2800.0)
    private val eth1 = IfaceSample("eth1", 0.0, 0.0)

    @Test fun picks_named_primary() =
        assertEquals(NetworkThroughput(3300.0, 2800.0), selectThroughput(listOf(lo, eth0, eth1), "eth0"))

    @Test fun never_picks_loopback_even_if_named() =
        assertEquals(NetworkThroughput(0.0, 0.0), selectThroughput(listOf(lo), "lo"))

    @Test fun fallback_to_active_non_lo_when_name_absent() =
        assertEquals(NetworkThroughput(3300.0, 2800.0), selectThroughput(listOf(lo, eth1, eth0), null))

    @Test fun fallback_to_first_non_lo_when_all_idle() =
        assertEquals(NetworkThroughput(0.0, 0.0), selectThroughput(listOf(lo, eth1), null))

    @Test fun empty_is_zero() =
        assertEquals(NetworkThroughput.ZERO, selectThroughput(emptyList(), "eth0"))
}
```

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/util/ByteFormat.kt \
        app/src/main/kotlin/io/github/nofuturekid/nova/data/model/NetworkThroughput.kt \
        app/src/test/kotlin/io/github/nofuturekid/nova/ui/util/ByteFormatTest.kt \
        app/src/test/kotlin/io/github/nofuturekid/nova/data/model/NetworkThroughputTest.kt
git commit -m "feat: NetworkThroughput model, pure interface-selection, byte formatter + tests"
```

---

## Task 3: Apollo mapper — generated frame → `IfaceSample` list

Thin adapter over the generated `SystemMetricsNetworkSubscription.Data`; selection itself is the tested `selectThroughput`. Requires Task 1's codegen, so it is compile-verified in CI only.

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/GraphQlMapper.kt`

- [ ] **Step 1: Add the mapper** (adjust the generated property path if Step-1 introspection differs):

```kotlin
import io.github.nofuturekid.nova.graphql.SystemMetricsNetworkSubscription
import io.github.nofuturekid.nova.data.model.IfaceSample

fun SystemMetricsNetworkSubscription.Data.toIfaceSamples(): List<IfaceSample> =
    systemMetricsNetwork.interfaces.map {
        IfaceSample(
            iface = it.iface,
            rxBytesPerSec = it.rxBytesPerSec ?: 0.0,
            txBytesPerSec = it.txBytesPerSec ?: 0.0,
        )
    }
```

- [ ] **Step 2: Commit.**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/api/GraphQlMapper.kt
git commit -m "feat(mapper): SystemMetricsNetwork frame -> IfaceSample list"
```

---

## Task 4: WS transport — `ApolloClientFactory.buildWs`

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/ApolloClientFactory.kt`

- [ ] **Step 1: Confirm the Apollo 5.0.0 WS API** via Context7 (`mcp__claude_ai_Context7__query-docs`, library "apollo kotlin") — exact names for `WebSocketNetworkTransport`, `GraphQLWsProtocol.Factory(connectionPayload = …)`, and how to supply an OkHttp-backed `WebSocketEngine`/`DefaultWebSocketEngine`. Do not guess the API surface.

- [ ] **Step 2: Extract the TLS-apply logic** so HTTP and WS share it. Refactor the existing `buildOn`'s OkHttp builder block (the `if (trust is TlsTrust.PinnedSelfSigned) { … sslSocketFactory … hostnameVerifier … }`) into a private helper `private fun OkHttpClient.Builder.applyTrust(trust: TlsTrust): OkHttpClient.Builder` and call it from both `buildOn` and the new WS path. (Surgical; preserves existing HTTP behaviour exactly.)

- [ ] **Step 3: Add `buildWs`** (shape — finalise names against Step 1):

```kotlin
fun buildWs(baseUrl: String, apiKey: String, trust: TlsTrust = TlsTrust.Default): ApolloClient =
    wsClients.computeIfAbsent(wsKey(baseUrl, apiKey, trust)) {
        val httpEndpoint = baseUrl.trimEnd('/') + "/graphql"
        val wsEndpoint = httpEndpoint.replaceFirst("http", "ws") // https->wss, http->ws
        val ws = baseHttpClient.newBuilder().applyTrust(trust).build()
        ApolloClient.Builder()
            .serverUrl(httpEndpoint)
            .subscriptionNetworkTransport(
                WebSocketNetworkTransport.Builder()
                    .serverUrl(wsEndpoint)
                    .protocol(GraphQLWsProtocol.Factory(connectionPayload = { mapOf("x-api-key" to apiKey) }))
                    .webSocketEngine(DefaultWebSocketEngine(ws))
                    .build()
            )
            .build()
    }
```

Add a `private val wsClients = ConcurrentHashMap<String, ApolloClient>()` and a `wsKey(...)` mirroring the existing `cached(...)` key (variant `"ws"`). Lifecycle ownership note same as ADR-0028 (factory owns; callers don't close).

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/api/ApolloClientFactory.kt
git commit -m "feat(api): buildWs WebSocket Apollo client reusing self-signed TLS pin"
```

---

## Task 5: Subscription substrate + network consumer + test seam

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/SubscriptionMappingTest.kt`

- [ ] **Step 1: Write the failing pure-seam test** (`SubscriptionMappingTest.kt`) — pins the substrate's per-sample → `DomainState` mapping + the network fallback, driven by a scripted flow (mirrors `pollDomainForTest`/`TransientErrorToleranceTest`):

```kotlin
package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.model.IfaceSample
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.selectThroughput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionMappingTest {
    @Test fun maps_each_frame_to_content() = runTest {
        val frames = listOf(
            listOf(IfaceSample("eth0", 100.0, 50.0)),
            listOf(IfaceSample("eth0", 200.0, 60.0)),
        )
        val out = UnraidRepository.subscriptionStreamForTest("https://x", frames) {
            selectThroughput(it, "eth0")
        }.toList()
        assertEquals(
            listOf(
                DomainState.Content(NetworkThroughput(100.0, 50.0), "https://x"),
                DomainState.Content(NetworkThroughput(200.0, 60.0), "https://x"),
            ),
            out,
        )
    }
}
```

- [ ] **Step 2: Add the `internal` test seam to `UnraidRepository.Companion`** (pure; no Apollo/WS):

```kotlin
internal fun <S, T> subscriptionStreamForTest(
    baseUrl: String,
    frames: List<S>,
    map: (S) -> T,
): kotlinx.coroutines.flow.Flow<DomainState<T>> =
    kotlinx.coroutines.flow.flow {
        for (f in frames) emit(DomainState.Content(map(f), baseUrl))
    }
```

- [ ] **Step 3: Add the production `subscriptionStream`** (mirrors `domainStream`: keyed on `servers.activeWithKey`, `flatMapLatest`, `NoServer`/blank-key `Error` cases identical to `domainStream`; collects `apolloFactory.buildWs(url, key, trust).subscription(op).toFlow()`, maps `data` → `DomainState.Content(map(data)).withBaseUrl(url)`; cold so the socket closes on cancel; on error emit `DomainState.Error`). Reuse `trustFor(active, url)`. No fallback poll for this consumer.

```kotlin
private fun <D : com.apollographql.apollo.api.Subscription.Data, T> subscriptionStream(
    op: com.apollographql.apollo.api.Subscription<D>,
    map: (D) -> T,
): Flow<DomainState<T>> = servers.activeWithKey
    .distinctUntilChanged()
    .flatMapLatest { active ->
        flow {
            if (active == null) { emit(DomainState.NoServer); return@flow }
            if (active.apiKey.isBlank()) {
                emit(DomainState.Error(blankKeyMessage(active))); return@flow
            }
            val url = activeUrl(active)
            val client = apolloFactory.buildWs(url, active.apiKey, trustFor(active, url))
            try {
                client.subscription(op).toFlow().collect { resp ->
                    val d = resp.data
                    if (d != null) emit(DomainState.Content(map(d), url))
                    else if (resp.exception != null) emit(DomainState.Error(resp.exception!!.message ?: "Live connection error"))
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emit(DomainState.Error(e.message ?: "Live connection error"))
            }
        }
    }
```

(Extract the existing blank-key message construction from `domainStream` into a private `blankKeyMessage(active)` helper and reuse in both — DRY, no behaviour change.)

- [ ] **Step 4: Add the network consumer:**

```kotlin
fun networkThroughputStream(): Flow<DomainState<NetworkThroughput>> {
    // Resolve the primary NIC name once; tolerate failure (fallback handles null).
    val primaryFlow = flow { emit(runCatching { resolvePrimaryIface() }.getOrNull()) }
    return primaryFlow.flatMapLatest { primary ->
        subscriptionStream(SystemMetricsNetworkSubscription()) { data ->
            selectThroughput(data.toIfaceSamples(), primary)
        }
    }
}

private suspend fun resolvePrimaryIface(): String? =
    activeClient()?.query(GetNetworkInterfacesQuery())?.execute()
        ?.data?.info?.primaryNetwork?.name
```

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt \
        app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/SubscriptionMappingTest.kt
git commit -m "feat(repo): generic subscriptionStream + networkThroughputStream + test seam"
```

---

## Task 6: MainViewModel — gated network state

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt`

- [ ] **Step 1: Add the gated state** next to `metricsState` (line ~214), gated identically so the WS socket lives only while Overview is foreground:

```kotlin
val networkThroughputState: StateFlow<DomainState<NetworkThroughput>> =
    gatedStream(overviewOnly(), unraid.networkThroughputStream())
```

Add the import `io.github.nofuturekid.nova.data.model.NetworkThroughput`.

- [ ] **Step 2: Commit.**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt
git commit -m "feat(vm): expose gated networkThroughputState (overview-only)"
```

---

## Task 7: UI wiring — feed the existing Network card

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/overview/OverviewTab.kt`

- [ ] **Step 1: Collect + pass the state in `MainScreen`.** Next to `val metricsState by vm.metricsState.collectAsState()` (line 107) add `val networkState by vm.networkThroughputState.collectAsState()`. In the `MainTab.Overview -> OverviewTab(...)` call (line ~218) add `networkThroughput = (networkState as? DomainState.Content)?.value`.

- [ ] **Step 2: Thread the param** through `OverviewTab(...)` into `OverviewContent(...)`: add parameter `networkThroughput: NetworkThroughput? = null` (import the model). 

- [ ] **Step 3: Drive the sparkline from the WS sample.** In `OverviewContent`, replace the hardcoded net tick. The existing `LaunchedEffect(metrics)` (lines 131-136) appends `0f` to `netSeries` — REMOVE that one line (`netSeries.value = netSeries.value.drop(1) + 0f`) and add a separate effect keyed on the live sample so it advances at the subscription's ~1 Hz, not the metrics cadence:

```kotlin
LaunchedEffect(networkThroughput) {
    val total = ((networkThroughput?.rxBytesPerSec ?: 0.0) +
                 (networkThroughput?.txBytesPerSec ?: 0.0)).toFloat()
    netSeries.value = netSeries.value.drop(1) + total
}
```

- [ ] **Step 4: Feed the card value/sub** (lines 260-271). Replace the hardcoded `value`/`sub`:

```kotlin
StatCard(
    iconColor = t.muted,
    icon = { UC.Network(18.dp, t.muted) },
    label = "Network",
    value = networkThroughput
        ?.let { formatBytesPerSec(it.rxBytesPerSec + it.txBytesPerSec) }
        ?: "—",
    sub = networkThroughput
        ?.let { "↓ ${formatBytesPerSec(it.rxBytesPerSec)}  ·  ↑ ${formatBytesPerSec(it.txBytesPerSec)}" }
        ?: "live unavailable",
    series = netSeries.value,
    seriesColor = Color(0xFFA78BFA),
    max = null,
)
```

Add `import io.github.nofuturekid.nova.ui.util.formatBytesPerSec`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt \
        app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/overview/OverviewTab.kt
git commit -m "feat(ui): live throughput in the Overview Network card + sparkline"
```

---

## Task 8: CI checkpoint — push and verify green

- [ ] **Step 1: Push the branch.**

```bash
git push -u origin feat/live-network-graph
```

- [ ] **Step 2: Watch CI** (the only build/test authority):

```bash
gh run watch --exit-status   # or: gh pr checks
```

Expected: `build` job green (Apollo codegen for `SystemMetricsNetworkSubscription` succeeds, `assembleDirectDebug`/`assembleRelease` + lint pass) AND `test` job green (`ByteFormatTest`, `NetworkThroughputTest`, `SubscriptionMappingTest` pass).

- [ ] **Step 3: If red, fix forward** (systematic-debugging): read the failing job log, correct the offending task's code, commit, push, re-watch. Common suspects: Apollo generated property path in Task 3 (adjust to match `schema.graphqls`), Apollo 5.0 WS builder API names in Task 4 (re-check Context7).

---

## Task 9: ADR-0042 + supersede ADR-0026

**Files:**
- Create: `docs/adr/0042-graphql-subscription-network-pilot.md` (use `/adr-new` or copy `docs/adr/template.md`)
- Modify: `docs/adr/0026-graphql-subscriptions-hybrid.md` (Status line)

- [ ] **Step 1: Write ADR-0042** "GraphQL subscription transport — network throughput pilot": Status `Proposed`; Context = ADR-0026 revisit (network field now exists upstream; agent-drivable test env), live evidence (rates deliver ~1 Hz). Decision = adopt the WS subscription transport scoped to network throughput only, reusing the self-signed pin (ADR-0041), gated `overviewOnly()`, **no poll fallback** (none exists), cold-flow socket lifecycle. Consequences/trade-offs; explicitly out of scope: metrics/docker/array. Note the `graphql-smoke` skill is HTTP-only (WS validated via python client / on-device).

- [ ] **Step 2: Update ADR-0026 Status** to: `Superseded by ADR-0042 for network throughput (network pilot); metrics/docker/array still pending.`

- [ ] **Step 3: Commit.**

```bash
git add docs/adr/0042-graphql-subscription-network-pilot.md docs/adr/0026-graphql-subscriptions-hybrid.md
git commit -m "docs(adr-0042): adopt WS subscription transport for network throughput pilot"
```

---

## Task 10: Version bump + CHANGELOG (beta-first)

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Bump** in `app/build.gradle.kts` `defaultConfig`: `versionCode = 108` (was 107), `versionName = "0.1.40-beta1"` (was "0.1.39").

- [ ] **Step 2: CHANGELOG** (ADR-0031, plain language) — under `## [Unreleased]` add to `### Added`:

```markdown
- The Network card on the dashboard now shows live upload/download speed in real time.
```

- [ ] **Step 3: Commit.**

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "release: 0.1.40-beta1 — live network throughput card (versionCode 108)"
```

---

## Task 11: PR, CI, beta tag — DELEGATE to `release-engineer`

Per CLAUDE.md Rule 14 the release tail is delegated to the `release-engineer` subagent. It opens the PR, watches CI, squash-merges on green, and tags `v0.1.40-beta1` (beta tagging is autonomous; **stable promotion stays the maintainer's on-device gate** — ADR-0027). The `0.1.40-beta1` APK ships for on-device acceptance: confirm the Network card shows live rates on the real server, then the WS-transport balloon is validated.

- [ ] **Step 1:** Dispatch `release-engineer` with the branch `feat/live-network-graph`, target `0.1.40-beta1`.
- [ ] **Step 2:** Maintainer on-device acceptance (human-only). Do NOT promote to stable.

---

## Self-review notes (for the executor)

- **Pure seams** (`selectThroughput`, `formatBytesPerSec`, `subscriptionStreamForTest`) are the only unit-tested logic — deliberate, because generated Apollo types and the live WS can't run in the `test` job. Mapper/transport are CI-compile-verified + on-device-verified.
- **Type consistency:** `NetworkThroughput(rxBytesPerSec, txBytesPerSec)`, `IfaceSample(iface, rxBytesPerSec, txBytesPerSec)`, `selectThroughput(samples, primaryIface)`, `formatBytesPerSec(Double)`, `buildWs(baseUrl, apiKey, trust)`, `networkThroughputStream()`, `networkThroughputState` — used identically across tasks.
- **Apollo API caveat:** Task 4 Step 1 and Task 3 Step 1 require verifying generated property paths + Apollo 5.0 WS builder names before finalising — flagged as the two real unknowns.
