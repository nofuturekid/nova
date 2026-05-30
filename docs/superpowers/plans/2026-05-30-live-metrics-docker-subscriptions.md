# 0.1.40 Live Metrics & Docker Stats via Subscriptions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Roll the ADR-0042 WebSocket subscription substrate out to CPU, memory, temperature, and per-container docker stats so the Overview cards and Docker tab update live, degrading to the existing 2 s HTTP poll on sustained WS failure (no blank card). **Architecture:** Reuse the proven `subscriptionStream<D,T>` + `buildWs` + `gatedStream` substrate unchanged; add one new generic `subscriptionOrPoll` poll-fallback combinator (for domains with a query path), a cpu+mem `combine` into the existing `LiveMetrics`, a new `Temperature` domain, and a docker stream-join overlay-by-id that never mutates `Container`. CPU/Mem/Temp share a single extended `GetMetrics` poll as the fallback floor; docker stats has no fallback (no query path). The polled-container × live-stats overlay is performed by the unit-tested pure `MainViewModel.joinContainerStats`, called from `DockerContent` over a VM-exposed overlay map. **Tech Stack:** Kotlin, Apollo Kotlin 5 (GraphQL over `graphql-transport-ws`), Kotlin Coroutines/Flow, Jetpack Compose (Material 3), JUnit + kotlinx-coroutines-test (virtual time), gated by lifecycle in `MainViewModel`.

<!-- CRITIQUE M3: The spec prose names SensorType/TemperatureStatus for vendoring, but NO selected field references them. Apollo codegen only needs types reachable from a selection, so vendoring them would be dead schema. We follow the operations, not the prose, and deliberately omit both. This is sound (verified: the only enum a selection forces is TemperatureUnit, via TemperatureReading.unit). -->
<!-- CRITIQUE m1/m4: build has NO allWarningsAsErrors / ktlint / detekt / spotless (verified: app/build.gradle.kts + root has none). So unchecked-cast warnings and unused-import lint cannot fail CI. We still keep imports tidy and @Suppress the one load-bearing covariant cast for hygiene, but this is not a blocker. -->

---

## File Structure

**GraphQL documents (Apollo codegen inputs)**
- `app/src/main/graphql/io/github/nofuturekid/nova/schema.graphqls` — **Modify:** vendor the 4 new Subscription roots, `Metrics.temperature`, the Temperature* type family + `TemperatureUnit` enum, and `DockerContainerStats`.
- `app/src/main/graphql/io/github/nofuturekid/nova/subscriptions.graphql` — **Modify:** add `SystemMetricsCpu`, `SystemMetricsMemory`, `SystemMetricsTemperature`, `DockerContainerStats` subscription operations.
- `app/src/main/graphql/io/github/nofuturekid/nova/queries.graphql` — **Modify:** extend `GetMetrics` with the temperature subtree (unified poll fallback).

**Substrate (repository)**
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt` — **Modify:** add `SUB_FALLBACK_GRACE_MS` const + `subscriptionOrPoll` combinator; `combineMetricsStates`-based `liveMetricsStream()`; rewrite `metricsStream()` to wrap live cpu+mem in `subscriptionOrPoll`; add `temperatureStream()`, `dockerStatsStream()`, and the `accumulateStats`/`metricsCombineForTest`/`dockerStatsStreamForTest` test seams.

**Models**
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/Temperature.kt` — **Create:** `Temperature` model, `TemperatureUnit` domain enum, `TempSummarySample` seam struct, `toTemperature` pure mapper, `Temperature.UNKNOWN` sentinel.
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/NetworkThroughput.kt` — **Modify:** add `ContainerLiveStats` data class (alongside the network pilot model).

**Mappers**
- `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/GraphQlMapper.kt` — **Modify:** add `combineLiveMetrics` (shared poll+live math), refactor `toLiveMetrics` to delegate to it, add cpu/mem frame extractors, temperature subscription+poll mappers + `TemperatureUnit.toDomain()`, and `DockerContainerStatsSubscription.Data.toContainerLiveStat()`.

**ViewModel + UI**
- `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt` — **Modify:** add gated `temperatureState` and `dockerStatsState`, the derived `dockerLiveStats` overlay map, plus the pure `joinContainerStats` overlay seam.
- `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt` — **Modify:** collect `temperatureState`/`dockerLiveStats` and thread them into `OverviewTab`/`DockerTab`.
- `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/overview/OverviewTab.kt` — **Modify:** new Temperature `StatCard` with M3 warn/critical accent escalation.
- `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/docker/DockerTab.kt` — **Modify:** thread the overlay map and apply `joinContainerStats`; render live cpu%/memory per row.

**Tests (Create)**
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/SubscriptionOrPollTest.kt` — degrade/recover/cold-start/healthy/NoServer transitions + grace const.
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/api/LiveMetricsMappingTest.kt` — `combineLiveMetrics` byte→GiB + null-buffcache parity with the poll.
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/MetricsCombineTest.kt` — cpu+mem combine via the real `combineMetricsStates`: waits for both Content, folds precedence (NoServer/Error/Loading), shared math.
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/model/TemperatureTest.kt` — model sentinel + pure `toTemperature` null-tolerance + unit-less-but-available case.
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TemperatureMappingTest.kt` — Apollo frame → `Temperature` (null root → UNKNOWN; null hottest → available, Unknown unit).
- `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/DockerStatsAccumulationTest.kt` — frame accumulation into the growing overlay map.
- `app/src/test/kotlin/io/github/nofuturekid/nova/ui/screens/main/ContainerStatsJoinTest.kt` — left-join keeps un-stat'd containers, preserves polled order.

**Docs / Release (Modify/Create)**
- `docs/adr/0043-graphql-subscription-metrics-docker-rollout.md` — **Create:** ADR recording the rollout, `subscriptionOrPoll`, cpu+mem combine, docker stream-join.
- `docs/adr/0026-graphql-subscriptions-hybrid.md` — **Modify:** Status line references ADR-0043.
- `CHANGELOG.md` — **Modify:** plain-language Added/Changed bullets; release-rename to `[0.1.40-beta2]`.
- `app/build.gradle.kts` — **Modify:** `versionName` 0.1.40-beta1 → 0.1.40-beta2 (versionCode stays 108).

---

### Task 1: Schema vendoring + 4 subscription ops + extended GetMetrics

This is the codegen-input task. There is no JDK/Android SDK locally — CI is the only build authority — so the "test" for this task is `./gradlew :app:generateApolloSources` succeeding in CI, which fails loudly on any unresolved type/scalar/selection. Vendor ONLY what an operation selects (the schema is a deliberate SUBSET). Ground truth (verified 2026-05-30): `Int/Float/String/Boolean` are built-in GraphQL scalars needing no `mapScalar`; `PrefixedID→kotlin.String` and `BigInt→kotlin.Long` are already mapped (`app/build.gradle.kts` lines 134-136). The only genuinely new named type forced by a selection is the `TemperatureUnit` enum. `TemperatureStatus`/`SensorType` are named in the spec prose but **no operation here selects them** — omit them (follow the operations, not the prose). Verified anchors: `subscriptions.graphql` lines 1-9 hold `SystemMetricsNetwork`; `queries.graphql` lines 33-38 hold `GetMetrics`; `schema.graphqls` line 136 is `type Metrics`, line 449 is `type Subscription` (line 450 = `systemMetricsNetwork: NetworkUtilization!`).

**Files:**
- Modify: `app/src/main/graphql/io/github/nofuturekid/nova/schema.graphqls`
- Modify: `app/src/main/graphql/io/github/nofuturekid/nova/subscriptions.graphql`
- Modify: `app/src/main/graphql/io/github/nofuturekid/nova/queries.graphql`

- [ ] **Step 1: Vendor the 4 new Subscription roots**
  Replace the current `type Subscription` block (line 449, today only `systemMetricsNetwork` at line 450) so it ALSO declares the four new roots with exact nullability from introspection: cpu/memory/dockerContainerStats are NON-NULL, temperature is NULLABLE. Do not reorder or remove `systemMetricsNetwork`.
  ```graphql
  type Subscription {
      systemMetricsNetwork: NetworkUtilization!
      systemMetricsCpu: CpuUtilization!
      systemMetricsMemory: MemoryUtilization!
      # Nullable on the live server (introspection 2026-05-30) — tolerate null frames.
      systemMetricsTemperature: TemperatureMetrics
      dockerContainerStats: DockerContainerStats!
  }
  ```

- [ ] **Step 2: Add `temperature` to the existing `Metrics` type**
  The current `type Metrics` (line 136) has only `cpu`/`memory`. Without this field the extended `GetMetrics` `temperature {...}` selection fails codegen ("Cannot query field temperature on type Metrics"). Nullable, mirroring `cpu`/`memory` and the nullable subscription root.
  ```graphql
  type Metrics {
      cpu: CpuUtilization
      memory: MemoryUtilization
      temperature: TemperatureMetrics
  }
  ```

- [ ] **Step 3: Vendor the Temperature* types + `TemperatureUnit` enum + `DockerContainerStats`**
  Append after the existing Subscription/Network block (after `NetworkInterfaceUtilization`), under the `── Subscriptions ──` section so all subscription-payload types sit together. Field set is EXACTLY what the operations select — nothing more. `DockerContainerStats` includes `netIO`/`blockIO` because the op selects them; it does NOT add `TemperatureStatus`/`SensorType`/status fields/sensor arrays.
  ```graphql
  type TemperatureMetrics {
      summary: TemperatureSummary!
  }

  type TemperatureSummary {
      average: Float!
      hottest: TemperatureSensor!
      warningCount: Int!
      criticalCount: Int!
  }

  type TemperatureSensor {
      name: String!
      current: TemperatureReading!
  }

  type TemperatureReading {
      value: Float!
      unit: TemperatureUnit!
  }

  enum TemperatureUnit {
      CELSIUS
      FAHRENHEIT
      KELVIN
      RANKINE
  }

  type DockerContainerStats {
      id: PrefixedID!
      cpuPercent: Float!
      memUsage: String!
      memPercent: Float!
      netIO: String!
      blockIO: String!
  }
  ```

- [ ] **Step 4: Add the four subscription operation documents**
  Append after the existing `SystemMetricsNetwork` op (after line 9) in `subscriptions.graphql`. Operation names are chosen so Apollo generates `SystemMetricsCpuSubscription` / `SystemMetricsMemorySubscription` / `SystemMetricsTemperatureSubscription` / `DockerContainerStatsSubscription` (mirroring `SystemMetricsNetwork` → `SystemMetricsNetworkSubscription`). cpu→`percentTotal`; memory→`total used buffcache` (same three `GetMetrics` selects); temperature and docker per the spec selections.
  ```graphql
  subscription SystemMetricsCpu {
    systemMetricsCpu {
      percentTotal
    }
  }

  subscription SystemMetricsMemory {
    systemMetricsMemory {
      total
      used
      buffcache
    }
  }

  subscription SystemMetricsTemperature {
    systemMetricsTemperature {
      summary {
        average
        hottest {
          name
          current {
            value
            unit
          }
        }
        warningCount
        criticalCount
      }
    }
  }

  subscription DockerContainerStats {
    dockerContainerStats {
      id
      cpuPercent
      memUsage
      memPercent
      netIO
      blockIO
    }
  }
  ```

- [ ] **Step 5: Extend the `GetMetrics` query with the temperature subtree**
  Replace the `GetMetrics` query (lines 33-38) so a single poll backs CPU+Mem+Temp. The temperature selection is FIELD-IDENTICAL to the `SystemMetricsTemperature` subscription so the poll and sub feed the same mapper with zero drift.
  ```graphql
  query GetMetrics {
      metrics {
          cpu { percentTotal }
          memory { total used buffcache }
          temperature {
              summary {
                  average
                  hottest {
                      name
                      current { value unit }
                  }
                  warningCount
                  criticalCount
              }
          }
      }
  }
  ```

- [ ] **Step 6: Run Apollo codegen (CI — the real test for this task)**
  This validates every vendored type/op/selection. Any unresolved type/scalar/selection fails loudly here. Expect generated classes (package `io.github.nofuturekid.nova.graphql`): `SystemMetricsCpuSubscription` (`.Data.systemMetricsCpu.percentTotal: Double`), `SystemMetricsMemorySubscription` (`.total/used: Long`, `.buffcache: Long?`), `SystemMetricsTemperatureSubscription` (`.systemMetricsTemperature?` nullable; `.summary.average: Double`, `.warningCount/.criticalCount: Int`, `.hottest.name: String`, `.hottest.current.value: Double`, `.hottest.current.unit: io.github.nofuturekid.nova.graphql.type.TemperatureUnit`), `DockerContainerStatsSubscription` (`.dockerContainerStats.id: String`, `.cpuPercent/.memPercent: Double`, `.memUsage/.netIO/.blockIO: String`). The widened `GetMetricsQuery.Data` gains `metrics.temperature?.summary`. The enum generates with `CELSIUS/FAHRENHEIT/KELVIN/RANKINE` plus Apollo's synthetic `UNKNOWN__`.
  ```bash
  ./gradlew :app:generateApolloSources
  ```
  Expected: PASS (codegen succeeds; new classes generated).

- [ ] **Step 7: Confirm existing tests still compile against the regenerated classes**
  The widened `GetMetrics` and new subscription `.Data` classes must not break existing mapper/network tests.
  ```bash
  ./gradlew :app:testDirectDebugUnitTest
  ```
  Expected: PASS.

- [ ] **Step 8: Commit the schema + operation documents**
  ```bash
  git add app/src/main/graphql/io/github/nofuturekid/nova/schema.graphqls \
          app/src/main/graphql/io/github/nofuturekid/nova/subscriptions.graphql \
          app/src/main/graphql/io/github/nofuturekid/nova/queries.graphql
  git commit -m "feat(graphql): vendor cpu/mem/temp/docker subscriptions + extend GetMetrics

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 2: Substrate — `subscriptionOrPoll` combinator + grace const (TDD)

The new substrate capability over ADR-0042's bare `subscriptionStream`: when a live sub Errors past `SUB_FALLBACK_GRACE_MS` (or never delivers a first frame), degrade to the HTTP poll; return to the sub on recovery. Poll is collected ONLY while in fallback (no double-collect). **`NoServer` is forwarded immediately, never sent through the grace/poll path** (there is no server at all — degrading to a poll that also has no server just wastes the grace window; verified `subscriptionStream` emits `DomainState.NoServer` when `active == null`). Pure `Flow<DomainState<T>> × Flow<DomainState<T>> → Flow<DomainState<T>>` — no Apollo types, no live WS — so the unit test drives the REAL production body on the virtual scheduler (Rule 9). Timing uses suspending primitives only (`withTimeoutOrNull`) so grace is deterministic. Canonical signature, reused by every consumer task: `internal fun <T> subscriptionOrPoll(sub: Flow<DomainState<T>>, poll: Flow<DomainState<T>>, graceMs: Long = SUB_FALLBACK_GRACE_MS): Flow<DomainState<T>>`.

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/SubscriptionOrPollTest.kt`

- [ ] **Step 1: Write the failing grace-const test**
  Create the test file. The first test asserts the grace const encodes its documented intent (multiple of, and strictly greater than, the metrics poll interval) — so it can't pass if the const is lowered to flap, and references the real symbol (not a magic number).
  ```kotlin
  package io.github.nofuturekid.nova.data.repository

  import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.subscriptionOrPoll
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.flow.MutableSharedFlow
  import kotlinx.coroutines.flow.flow
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.test.TestScope
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Test

  /**
   * Substrate-fallback — [UnraidRepository.Companion.subscriptionOrPoll].
   *
   * WHY (Rule 9 + ADR-0026): metrics/temperature have a query path, so a
   * sustained WS drop must degrade to the 2 s HTTP poll rather than blank the
   * card. These tests pin the degrade/recover state machine, the NoServer
   * passthrough, and the grace timing through the REAL production combinator
   * (no re-derived copy), driven on the virtual test scheduler so grace is
   * deterministic.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  class SubscriptionOrPollTest {

      @Test
      fun graceConstantIsSustainedFailureSlackOverThePollInterval() {
          // WHY: 6 s grace = 3 metrics poll cycles. Fallback must mean
          // "sustained sub failure", not a single ~1 Hz frame jitter; and it
          // must be strictly larger than one poll interval so a lone blip can't
          // yank the card to the poll path. Fails if the const is set <= the
          // poll interval (would flap) or made non-multiple (loses the ratio).
          assertTrue(
              UnraidRepository.SUB_FALLBACK_GRACE_MS > UnraidRepository.POLL_METRICS_MS,
          )
          assertEquals(
              0L,
              UnraidRepository.SUB_FALLBACK_GRACE_MS % UnraidRepository.POLL_METRICS_MS,
          )
      }
  }
  ```

- [ ] **Step 2: Run the test — expect FAIL (unresolved symbols)**
  Confirms the test is wired to the real (not-yet-written) symbols.
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests 'io.github.nofuturekid.nova.data.repository.SubscriptionOrPollTest'
  ```
  Expected: FAIL (compile error: unresolved `SUB_FALLBACK_GRACE_MS` / `subscriptionOrPoll`).

- [ ] **Step 3: Add the `SUB_FALLBACK_GRACE_MS` const**
  Add to the companion object immediately after `TRANSIENT_ERROR_TOLERANCE` (line 110). The KDoc justifies the value against `POLL_METRICS_MS` so the coupling is visible.
  ```kotlin
  /**
   * Grace window before [subscriptionOrPoll] degrades a live subscription to
   * its HTTP poll fallback. Chosen at 3x [POLL_METRICS_MS] (6 s): long enough
   * that a single missed ~1 Hz frame, a brief WS reconnect, or one jittery
   * delivery does NOT yank the card onto the poll path (that would defeat the
   * live update); short enough that a genuine sustained WS outage degrades to
   * the poll within ~6 s, well under the user's annoyance threshold and never
   * leaving a blank card (ADR-0026). If [POLL_METRICS_MS] ever changes, revisit
   * this 3-cycle ratio.
   */
  const val SUB_FALLBACK_GRACE_MS = 6_000L
  ```

- [ ] **Step 4: Add the combinator imports**
  Add to the top import block of `UnraidRepository.kt` (keep existing imports; `distinctUntilChanged`/`flatMapLatest` already present at lines 13/15):
  ```kotlin
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.channelFlow
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.flow.transformLatest
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withTimeoutOrNull
  ```

- [ ] **Step 5: Implement `subscriptionOrPoll`**
  Add as an `internal` companion function directly after `subscriptionStreamForTest` (line 221, still inside the companion). A child coroutine collects `sub` into a `MutableStateFlow<DomainState<T>?>`; the outer `transformLatest` switches mode on the latest sub state. On `Content` → forward. On `NoServer` → forward immediately (no grace, no poll). On `null`/`Error`/`Loading` → `withTimeoutOrNull(graceMs)` awaits a recovery `Content`; **on recovery emit that recovered Content directly** (do NOT rely on `transformLatest` re-firing — see CRITIQUE M2 below); on grace expiry `poll.collect { emit(it) }`. `transformLatest` keyed on `subState` cancels the in-flight poll the instant a new sub state arrives (cancel-on-recovery, no double-collect).
  <!-- CRITIQUE M2 (VALID, high-risk): the draft relied on "emit NOTHING on recovery and let transformLatest re-fire into the Content branch". That is fragile: transformLatest only re-runs the lambda on a NEW upstream emission, and the recovery value was already consumed by first{} — the re-fire is not guaranteed and would drop the recovery frame, failing subRecoveryReturnsToSubAndStopsPoll. FIX: emit the recovered Content directly. The Content branch then handles only fresh post-recovery frames. -->
  <!-- CRITIQUE B5 (VALID): the draft's `else` swallowed NoServer into the grace/poll path. FIX: handle NoServer explicitly as an immediate passthrough; tested by noServerForwardsImmediatelyWithoutGrace. Loading stays in the wait-for-Content branch (a cold sub that emits Loading should wait for its first Content, same as the silent-cold-start case). -->
  ```kotlin
  /**
   * Generic subscription-with-poll-fallback combinator (ADR-0026 / ADR-0043).
   *
   * [sub] is primary: its [DomainState.Content] frames are forwarded as-is, and
   * [DomainState.NoServer] is forwarded immediately (no server at all — never
   * worth burning the grace window on a poll that also has no server). The
   * stream degrades to [poll] when the sub yields a [DomainState.Error] (or
   * [DomainState.Loading], or no first frame) held continuously for [graceMs].
   * It returns to [sub] the instant the sub recovers a [DomainState.Content]
   * frame — that recovered frame is emitted directly. [poll] is collected ONLY
   * while in the fallback state — never while the sub is healthy (no
   * double-collect: the HTTP poll must not run alongside a working WS).
   *
   * This is the NEW substrate capability over ADR-0042's bare
   * [subscriptionStream] (the network balloon had no query path, so no
   * fallback). It is a pure [DomainState] combinator — no Apollo types, no live
   * WS — so the unit test drives this exact production body on the virtual
   * scheduler (Rule 9), with [graceMs] timed by suspending primitives only (no
   * wall-clock) for determinism.
   *
   * `internal` (same-module), not `private`, mirroring the
   * [pollDomainForTest] / [subscriptionStreamForTest] seam convention so the
   * test reaches it without a repository instance.
   */
  internal fun <T> subscriptionOrPoll(
      sub: Flow<DomainState<T>>,
      poll: Flow<DomainState<T>>,
      graceMs: Long = SUB_FALLBACK_GRACE_MS,
  ): Flow<DomainState<T>> = channelFlow {
      // Latest sub state; null until the sub's first frame. A child coroutine
      // keeps this fresh while the main body decides routing.
      val subState = MutableStateFlow<DomainState<T>?>(null)
      launch { sub.collect { subState.value = it } }

      // Route on the latest sub state. transformLatest cancels the prior branch
      // whenever subState changes: a fresh sub Content cancels any in-flight
      // poll collection (recovery), and an Error/null branch is replaced the
      // moment a new state arrives.
      subState.transformLatest { latest ->
          when (latest) {
              is DomainState.Content<T> -> emit(latest)        // healthy: poll NOT collected
              DomainState.NoServer -> emit(DomainState.NoServer) // no server: immediate passthrough
              else -> {
                  // null (no first Content yet), Error, or Loading: wait out the
                  // grace window for a Content to (re)appear. On recovery, emit
                  // that Content DIRECTLY (do not rely on a transformLatest
                  // re-fire — that can drop the frame). On grace expiry, degrade
                  // to poll; collecting inside this lambda means the poll is
                  // cancelled the instant subState changes back to Content.
                  val recovered = withTimeoutOrNull(graceMs) {
                      subState.first { it is DomainState.Content<T> }
                  }
                  if (recovered != null) {
                      emit(recovered)
                  } else {
                      poll.collect { emit(it) }
                  }
              }
          }
      }.collect { send(it) }
  }
  ```

- [ ] **Step 6: Add test (a) — healthy sub never collects poll**
  Append to the test class. A sub that immediately and continuously emits Content; the poll flags collection via a side-effecting flow. Assert only sub Content is emitted AND the poll was never collected, even after advancing past `graceMs`.
  ```kotlin
      @Test
      fun healthySubEmitsOnlySubContentAndNeverCollectsPoll() = runTest {
          val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
          var pollCollected = false
          val sub = MutableSharedFlow<DomainState<String>>(replay = 1)
          val poll = flow<DomainState<String>> {
              pollCollected = true
              emit(DomainState.Content("POLL", "http://srv"))
          }
          val out = mutableListOf<DomainState<String>>()
          val job = scope.launch {
              subscriptionOrPoll(sub, poll, graceMs = UnraidRepository.SUB_FALLBACK_GRACE_MS)
                  .collect { out += it }
          }
          sub.emit(DomainState.Content("S1", "http://srv"))
          scope.testScheduler.runCurrent()
          // Push past the grace window with the sub still healthy — fallback must NOT trip.
          scope.testScheduler.advanceTimeBy(UnraidRepository.SUB_FALLBACK_GRACE_MS * 2)
          scope.testScheduler.runCurrent()
          sub.emit(DomainState.Content("S2", "http://srv"))
          scope.testScheduler.runCurrent()

          // WHY: a healthy live sub must drive the card alone; collecting the
          // HTTP poll concurrently is a double-collect defect (server load +
          // pointless 2 s churn). Fails if the impl starts the poll eagerly or
          // lets the grace timer fire while Content is arriving.
          assertEquals(false, pollCollected)
          assertEquals(
              listOf(
                  DomainState.Content("S1", "http://srv"),
                  DomainState.Content("S2", "http://srv"),
              ),
              out,
          )
          job.cancel()
      }
  ```

- [ ] **Step 7: Run — expect PASS for the const + healthy-sub tests**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests 'io.github.nofuturekid.nova.data.repository.SubscriptionOrPollTest'
  ```
  Expected: PASS.

- [ ] **Step 8: Commit the const + combinator + first two tests**
  ```bash
  git add app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt \
          app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/SubscriptionOrPollTest.kt
  git commit -m "feat(repo): subscriptionOrPoll fallback combinator + grace const

New substrate capability over the ADR-0042 bare subscriptionStream: when a
live sub Errors past SUB_FALLBACK_GRACE_MS (or never delivers a first frame),
degrade to the HTTP poll; return to the sub on recovery. NoServer is forwarded
immediately. Poll is collected only while in fallback (no double-collect).
ADR-0026 mandate: no blank card.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

- [ ] **Step 9: Add test (b) — sustained Error past grace falls back to poll**
  The ADR-0026 mandate: within grace a transient Error keeps the last live frame; past grace the poll Content drives the card — never a blank/Error. The combinator deliberately swallows sub Errors (the poll/last-Content is the user-facing truth).
  ```kotlin
      @Test
      fun sustainedSubErrorPastGraceFallsBackToPollContentNotBlank() = runTest {
          val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
          val sub = MutableSharedFlow<DomainState<String>>(replay = 1)
          val poll = flow<DomainState<String>> { emit(DomainState.Content("POLL", "http://srv")) }
          val out = mutableListOf<DomainState<String>>()
          val job = scope.launch {
              subscriptionOrPoll(sub, poll, graceMs = UnraidRepository.SUB_FALLBACK_GRACE_MS)
                  .collect { out += it }
          }
          sub.emit(DomainState.Content("S1", "http://srv"))
          scope.testScheduler.runCurrent()
          sub.emit(DomainState.Error("ws dropped"))
          scope.testScheduler.runCurrent()

          // WHY: within the grace window a transient Error must NOT blank the
          // card — the last live frame stands. Fails if the impl forwards Error
          // or switches to poll immediately (graceMs ignored).
          scope.testScheduler.advanceTimeBy(UnraidRepository.SUB_FALLBACK_GRACE_MS - 1)
          scope.testScheduler.runCurrent()
          assertEquals(DomainState.Content("S1", "http://srv"), out.last())

          // WHY (ADR-0026 mandate): once the Error is sustained PAST the grace
          // window, the poll's Content must drive the card — never a blank/Error.
          scope.testScheduler.advanceTimeBy(2)
          scope.testScheduler.runCurrent()
          assertEquals(DomainState.Content("POLL", "http://srv"), out.last())
          job.cancel()
      }
  ```

- [ ] **Step 10: Add test (c) — recovery returns to sub and stops poll**
  After falling back, a fresh sub Content must re-take the card AND the poll collection must be cancelled (no lingering double-collect). The poll ticks on an interval so an un-cancelled poll would append POLL frames after recovery. This directly exercises the **emit-recovered-Content-directly** fix (M2).
  ```kotlin
      @Test
      fun subRecoveryReturnsToSubAndStopsPoll() = runTest {
          val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
          val sub = MutableSharedFlow<DomainState<String>>(replay = 1)
          val poll = flow<DomainState<String>> {
              var i = 0
              while (true) {
                  emit(DomainState.Content("POLL$i", "http://srv"))
                  i++
                  kotlinx.coroutines.delay(UnraidRepository.POLL_METRICS_MS)
              }
          }
          val out = mutableListOf<DomainState<String>>()
          val job = scope.launch {
              subscriptionOrPoll(sub, poll, graceMs = UnraidRepository.SUB_FALLBACK_GRACE_MS)
                  .collect { out += it }
          }
          sub.emit(DomainState.Content("S1", "http://srv"))
          scope.testScheduler.runCurrent()
          sub.emit(DomainState.Error("ws dropped"))
          scope.testScheduler.runCurrent()
          scope.testScheduler.advanceTimeBy(UnraidRepository.SUB_FALLBACK_GRACE_MS + UnraidRepository.POLL_METRICS_MS)
          scope.testScheduler.runCurrent()
          assertTrue(out.last() is DomainState.Content && (out.last() as DomainState.Content).value.startsWith("POLL"))

          // Sub recovers.
          sub.emit(DomainState.Content("S2", "http://srv"))
          scope.testScheduler.runCurrent()
          // WHY: recovery must immediately re-take the card with the live frame.
          // This pins the emit-recovered-Content-directly contract — a re-fire-
          // based impl would drop S2 and leave a stale POLL frame here.
          assertEquals(DomainState.Content("S2", "http://srv"), out.last())

          // Advance several poll intervals: if the poll were still collected it
          // would append POLLn frames after S2. It must not — recovery cancels
          // the poll branch. Fails on double-collect.
          scope.testScheduler.advanceTimeBy(UnraidRepository.POLL_METRICS_MS * 3)
          scope.testScheduler.runCurrent()
          assertEquals(DomainState.Content("S2", "http://srv"), out.last())
          job.cancel()
      }
  ```

- [ ] **Step 11: Add test (d) — silent sub from start uses poll until first frame**
  Cold-start: a sub that never delivers within grace must not blank the card — use the poll until the sub's first Content, then switch. Exercises the `subState == null` path distinct from the Error path.
  ```kotlin
      @Test
      fun silentSubFromStartUsesPollUntilFirstSubContent() = runTest {
          val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
          val sub = MutableSharedFlow<DomainState<String>>(replay = 1)
          val poll = flow<DomainState<String>> { emit(DomainState.Content("POLL", "http://srv")) }
          val out = mutableListOf<DomainState<String>>()
          val job = scope.launch {
              subscriptionOrPoll(sub, poll, graceMs = UnraidRepository.SUB_FALLBACK_GRACE_MS)
                  .collect { out += it }
          }
          // Sub silent. Before grace expiry: nothing emitted — waiting for a
          // first sub frame.
          scope.testScheduler.advanceTimeBy(UnraidRepository.SUB_FALLBACK_GRACE_MS - 1)
          scope.testScheduler.runCurrent()
          assertTrue(out.none { it is DomainState.Content && (it as DomainState.Content).value == "POLL" })

          // Past grace with the sub still silent -> poll fills the card.
          scope.testScheduler.advanceTimeBy(2)
          scope.testScheduler.runCurrent()
          assertEquals(DomainState.Content("POLL", "http://srv"), out.last())

          // The sub finally delivers its first frame -> switch to the live sub.
          sub.emit(DomainState.Content("S1", "http://srv"))
          scope.testScheduler.runCurrent()
          assertEquals(DomainState.Content("S1", "http://srv"), out.last())
          job.cancel()
      }
  ```

- [ ] **Step 12: Add test (e) — NoServer is forwarded immediately, no grace, no poll**
  <!-- CRITIQUE B5 fix verification: NoServer must pass through at once, never burn the grace window. -->
  ```kotlin
      @Test
      fun noServerForwardsImmediatelyWithoutGraceOrPoll() = runTest {
          val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
          var pollCollected = false
          val sub = MutableSharedFlow<DomainState<String>>(replay = 1)
          val poll = flow<DomainState<String>> {
              pollCollected = true
              emit(DomainState.Content("POLL", "http://srv"))
          }
          val out = mutableListOf<DomainState<String>>()
          val job = scope.launch {
              subscriptionOrPoll(sub, poll, graceMs = UnraidRepository.SUB_FALLBACK_GRACE_MS)
                  .collect { out += it }
          }
          sub.emit(DomainState.NoServer)
          scope.testScheduler.runCurrent()

          // WHY: NoServer means no active server at all. It must surface
          // immediately (the UI shows the add-server state), NOT wait out the
          // grace window and NOT collect a poll that also has no server. Fails
          // if NoServer is folded into the Error/fallback branch (the draft bug).
          assertEquals(DomainState.NoServer, out.last())
          assertEquals(false, pollCollected)
          job.cancel()
      }
  ```

- [ ] **Step 13: Run the full class + repository package — expect all PASS**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests 'io.github.nofuturekid.nova.data.repository.*'
  ```
  Expected: PASS (all 6 SubscriptionOrPollTest cases + no regression in sibling seams).

- [ ] **Step 14: Commit tests (b)–(e)**
  ```bash
  git add app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/SubscriptionOrPollTest.kt
  git commit -m "test(repo): subscriptionOrPoll degrade/recover/cold-start/no-server transitions

ADR-0026: transient error within grace keeps the last frame; sustained error or
silent cold-start past grace uses the poll; recovery returns to the sub (the
recovered frame emitted directly) and stops the poll (no lingering
double-collect); NoServer is forwarded immediately.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 3: Models — Temperature (model + sentinel + pure mapper) & ContainerLiveStats (TDD)

Two pure domain types with zero Apollo dependency, so they compile and unit-test before codegen-dependent work. `Temperature.available` is the load-bearing flag: it lets the card tell a genuine 0° reading apart from no-data, and is keyed on the presence of a usable `average` (not the hottest sensor). `TemperatureUnit` is a carried domain enum (never hardcoded Celsius), mirroring every other G-enum→domain mapper. `ContainerLiveStats` lands next to `NetworkThroughput` (same-file convention as the network pilot model). Verified ground truth: `Container(id, name, image, status, autoStart, iconColorHex, iconUrl, cpu, memMb, ports: List<String>, volumes: List<String>, updateStatus, webUiUrl, networkIp)`; `LiveMetrics(cpuPercent, memTotalGb, memUsedGb, memBuffGb)` all `Double`.

**Files:**
- Create: `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/Temperature.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/model/NetworkThroughput.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/model/TemperatureTest.kt`

- [ ] **Step 1: Write the failing model test (sentinel semantics)**
  Pin WHY the sentinel exists: UNKNOWN must report unavailable, carry no unit assumption, zero counts — and a real reading at 0° is still `available`.
  ```kotlin
  package io.github.nofuturekid.nova.data.model

  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class TemperatureTest {
      @Test fun unknown_sentinel_is_unavailable_and_unit_agnostic() {
          val t = Temperature.UNKNOWN
          // WHY: a null/absent temperature root must be visibly 'no data',
          // never an authoritative 0-degree reading (which would mislead).
          assertFalse(t.available)
          assertEquals(TemperatureUnit.Unknown, t.unit)
          assertEquals(0, t.warningCount)
          assertEquals(0, t.criticalCount)
          assertEquals("", t.hottestName)
      }

      @Test fun a_real_reading_is_available_even_at_zero_degrees() {
          // WHY: 0 degrees is a legitimate value; the `available` flag — not the
          // numeric average — is what distinguishes a real reading from absence.
          val t = Temperature(
              available = true, average = 0.0, unit = TemperatureUnit.Celsius,
              hottestName = "CPU", hottestValue = 0.0, warningCount = 0, criticalCount = 0,
          )
          assertTrue(t.available)
      }
  }
  ```

- [ ] **Step 2: Run — expect FAIL (types undefined)**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests 'io.github.nofuturekid.nova.data.model.TemperatureTest'
  ```
  Expected: FAIL.

- [ ] **Step 3: Create the model + sentinel**
  ```kotlin
  package io.github.nofuturekid.nova.data.model

  /** Temperature display unit carried from the server — never assumed.
   *  [Unknown] is the sentinel value used when no reading is available. */
  enum class TemperatureUnit { Celsius, Fahrenheit, Kelvin, Rankine, Unknown }

  /**
   * Live temperature summary for the Overview card.
   *
   * [available] is the load-bearing flag: the `systemMetricsTemperature` root is
   * NULLABLE on the live server (introspection 2026-05-30), so absence must be
   * representable distinctly from a genuine 0-degree reading. When [available]
   * is false the card shows 'unavailable'; otherwise it shows [average] [unit].
   * [unit] is carried from the server (never hardcoded Celsius); it MAY be
   * [TemperatureUnit.Unknown] on an available reading whose hottest sensor is
   * absent — see [toTemperature]/the mapper tests for that intentional case.
   */
  data class Temperature(
      val available: Boolean,
      val average: Double,
      val unit: TemperatureUnit,
      val hottestName: String,
      val hottestValue: Double,
      val warningCount: Int,
      val criticalCount: Int,
  ) {
      companion object {
          /** No-data sentinel for a null temperature root / null summary. */
          val UNKNOWN = Temperature(
              available = false,
              average = 0.0,
              unit = TemperatureUnit.Unknown,
              hottestName = "",
              hottestValue = 0.0,
              warningCount = 0,
              criticalCount = 0,
          )
      }
  }
  ```

- [ ] **Step 4: Run — expect PASS**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests 'io.github.nofuturekid.nova.data.model.TemperatureTest'
  ```
  Expected: PASS.

- [ ] **Step 5: Add the failing mapper-seam tests**
  Append to `TemperatureTest`: a full summary maps through (incl. unit + counts), a null sample → UNKNOWN, a null average → UNKNOWN, and **a usable average with no unit still reads as available with `unit == Unknown`** (the intentional partial case — CRITIQUE M4). These encode the nullable-root contract (no NPE, no fake 0°), unit-carrying, and the documented partial-frame behavior.
  <!-- CRITIQUE M4 (VALID): the mapper can produce available=true, unit=Unknown when the average is present but hottest is null. The draft left this state untested while the model invariant test implied Unknown⇒unavailable. We resolve the contradiction explicitly: availability keys on the AVERAGE (the headline number), not on the hottest sensor. A reading with a real average but no named hottest sensor is still a real reading; the unit falls back to Unknown and the card omits the unit symbol. This case is now pinned by a test so it cannot silently regress. -->
  ```kotlin
      @Test fun full_summary_maps_with_unit_and_counts_carried() {
          // WHY: average + hottest + the warning/critical counts must all survive
          // mapping, and the unit must be carried from the server (Fahrenheit
          // here, NOT silently coerced to Celsius).
          val out = toTemperature(
              TempSummarySample(
                  average = 41.5, unit = TemperatureUnit.Fahrenheit,
                  hottestName = "CPU Package", hottestValue = 58.0,
                  warningCount = 2, criticalCount = 1,
              ),
          )
          assertEquals(
              Temperature(
                  available = true, average = 41.5, unit = TemperatureUnit.Fahrenheit,
                  hottestName = "CPU Package", hottestValue = 58.0,
                  warningCount = 2, criticalCount = 1,
              ),
              out,
          )
      }

      @Test fun null_summary_maps_to_unknown_sentinel() {
          // WHY: systemMetricsTemperature is NULLABLE — a null frame must degrade
          // to the no-data sentinel, never throw, never fabricate a 0° reading.
          assertEquals(Temperature.UNKNOWN, toTemperature(null))
      }

      @Test fun null_average_is_treated_as_no_data() {
          // WHY: a summary with no usable average is functionally absent;
          // surfacing it as 0 degrees would be a lie. Degrade to the sentinel.
          val out = toTemperature(
              TempSummarySample(
                  average = null, unit = TemperatureUnit.Celsius,
                  hottestName = "CPU", hottestValue = 50.0,
                  warningCount = 0, criticalCount = 0,
              ),
          )
          assertEquals(Temperature.UNKNOWN, out)
      }

      @Test fun usable_average_without_unit_is_available_with_unknown_unit() {
          // WHY (intentional partial frame): a real average with NO hottest
          // sensor (so unit defaults to Unknown) is still a real reading — the
          // headline number drives `available`, not the sensor name. The card
          // shows the number with no unit symbol; it does NOT collapse to
          // 'unavailable'. Pins the documented available && unit==Unknown state.
          val out = toTemperature(
              TempSummarySample(
                  average = 44.0, unit = TemperatureUnit.Unknown,
                  hottestName = null, hottestValue = null,
                  warningCount = 0, criticalCount = 0,
              ),
          )
          assertEquals(
              Temperature(
                  available = true, average = 44.0, unit = TemperatureUnit.Unknown,
                  hottestName = "", hottestValue = 0.0, warningCount = 0, criticalCount = 0,
              ),
              out,
          )
      }
  ```

- [ ] **Step 6: Run — expect FAIL (seam undefined)**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests 'io.github.nofuturekid.nova.data.model.TemperatureTest'
  ```
  Expected: FAIL.

- [ ] **Step 7: Add the seam struct + pure mapper to `Temperature.kt`**
  The ONE function both Apollo paths (subscription + poll) reuse. Null sample OR null average → UNKNOWN; otherwise carry every field, defaulting only secondary nulls. Availability keys on `average` (the headline), per M4.
  ```kotlin
  /** Plain, Apollo-free projection of a TemperatureSummary — the pure seam the
   *  Apollo mappers feed so [toTemperature] unit-tests without generated types
   *  (mirrors [IfaceSample] / [selectThroughput]). [unit] is already the domain
   *  enum so unit-carrying is exercised here, not buried in generated code. */
  data class TempSummarySample(
      val average: Double?,
      val unit: TemperatureUnit,
      val hottestName: String?,
      val hottestValue: Double?,
      val warningCount: Int?,
      val criticalCount: Int?,
  )

  /**
   * The single Temperature mapping function, reused by BOTH the subscription
   * frame and the GetMetrics poll (zero mapper drift). A null [sample] or a null
   * [average] degrades to [Temperature.UNKNOWN] — the root is nullable on the
   * live server and a missing average must read as 'no data', never a fake 0.
   * A present [average] yields an AVAILABLE reading even when the hottest sensor
   * (and thus [unit]) is absent: the headline number, not the sensor, defines
   * availability (see usable_average_without_unit_is_available_with_unknown_unit).
   */
  fun toTemperature(sample: TempSummarySample?): Temperature {
      val avg = sample?.average ?: return Temperature.UNKNOWN
      // `sample?.average ?: return` does NOT smart-cast `sample` to non-null
      // (the null check is on the property, not the receiver), so assert it:
      // a null `sample` would already have triggered the Elvis return above.
      return sample!!.let {
          Temperature(
              available = true,
              average = avg,
              unit = it.unit,
              hottestName = it.hottestName.orEmpty(),
              hottestValue = it.hottestValue ?: 0.0,
              warningCount = it.warningCount ?: 0,
              criticalCount = it.criticalCount ?: 0,
          )
      }
  }
  ```

- [ ] **Step 8: Run — expect PASS (all 6 cases)**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests 'io.github.nofuturekid.nova.data.model.TemperatureTest'
  ```
  Expected: PASS.

- [ ] **Step 9: Add `ContainerLiveStats` to NetworkThroughput.kt**
  Append the docker model alongside the network pilot model. No ZERO companion — absent stats = omit (no zero noise). Trivial type; behaviour is pinned by the accumulate/join tests in later tasks.
  ```kotlin
  /** Live per-container resource stats from the `dockerContainerStats`
   *  subscription. [cpuPercent]/[memPercent] are the server's percentages;
   *  [memUsage] is the server's already-formatted memory string (e.g.
   *  "1.2GiB / 16GiB"). Accumulated by id into the overlay map — a container
   *  with no frame yet simply has no entry (rows omit live stats, no zero noise). */
  data class ContainerLiveStats(
      val cpuPercent: Double,
      val memPercent: Double,
      val memUsage: String,
  )
  ```

- [ ] **Step 10: Commit the models + Temperature tests**
  ```bash
  git add app/src/main/kotlin/io/github/nofuturekid/nova/data/model/Temperature.kt \
          app/src/main/kotlin/io/github/nofuturekid/nova/data/model/NetworkThroughput.kt \
          app/src/test/kotlin/io/github/nofuturekid/nova/data/model/TemperatureTest.kt
  git commit -m "feat(model): Temperature (+UNKNOWN sentinel, pure toTemperature) + ContainerLiveStats

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 4: Mappers — combineLiveMetrics (shared poll+live math) + frame extractors + temperature/docker mappers (TDD)

The live cpu+mem combine must produce a `LiveMetrics` byte-identical to the `GetMetrics` poll, or swapping transports would visibly change the cards. `bytesToGib` (= `/1_073_741_824.0`) is PRIVATE in `GraphQlMapper.kt` (verified, line 395), so the shared helper MUST live there and `toLiveMetrics` (lines 77-85) is refactored to delegate (provably ONE math implementation). The thin Apollo extractors and the temperature/docker mappers feed the pure seams from Task 3. Apollo maps GraphQL `Float` → Kotlin `Double`, so `percentTotal` is already `Double`.

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/api/GraphQlMapper.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/api/LiveMetricsMappingTest.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TemperatureMappingTest.kt`

- [ ] **Step 1: Write the failing combineLiveMetrics test**
  Pin the binary-GiB divisor and the null-buffcache→0.0 rule, so a future edit diverging from the poll fails.
  ```kotlin
  package io.github.nofuturekid.nova.data.api

  import io.github.nofuturekid.nova.data.model.LiveMetrics
  import org.junit.Assert.assertEquals
  import org.junit.Test

  class LiveMetricsMappingTest {
      private val GiB = 1_073_741_824L

      // WHY: the live cpu+mem combine must yield the SAME LiveMetrics as the
      // GetMetrics poll (toLiveMetrics), so swapping WS<->poll is invisible.
      // Pins the binary-GiB divisor (RAM uses GiB, not decimal GB) and the
      // straight passthrough of cpuPercent.
      @Test fun converts_bytes_to_binary_gib_like_the_poll() {
          val m = combineLiveMetrics(
              cpuPercent = 37.5,
              memTotal = 8 * GiB,
              memUsed = 2 * GiB,
              memBuffcache = 1 * GiB,
          )
          assertEquals(LiveMetrics(37.5, 8.0, 2.0, 1.0), m)
      }

      // WHY: MemoryUtilization.buffcache is nullable on the live server. The
      // poll maps null -> 0L -> 0.0 GB. The live mapper MUST match, or a card
      // would read differently on the two transports.
      @Test fun null_buffcache_is_zero_gb_matching_the_poll() {
          val m = combineLiveMetrics(
              cpuPercent = 0.0,
              memTotal = 4 * GiB,
              memUsed = 1 * GiB,
              memBuffcache = null,
          )
          assertEquals(LiveMetrics(0.0, 4.0, 1.0, 0.0), m)
      }
  }
  ```

- [ ] **Step 2: Run — expect FAIL (combineLiveMetrics unresolved)**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests "*.LiveMetricsMappingTest"
  ```
  Expected: FAIL.

- [ ] **Step 3: Add `combineLiveMetrics` and refactor `toLiveMetrics` to delegate**
  In the `// ── Metrics ──` section of `GraphQlMapper.kt` (line 75), directly under `toLiveMetrics`. One math implementation reused by both transports.
  ```kotlin
  /**
   * The byte->GiB + buffcache math shared by the GetMetrics poll
   * ([toLiveMetrics]) and the live cpu+mem subscription combine. ONE
   * implementation so the two transports are provably byte-identical (Rule 9):
   * if this changes, both paths change together.
   *
   * [memBuffcache] is nullable because MemoryUtilization.buffcache is nullable
   * on the live server; null is treated as 0 bytes -> 0.0 GB, matching the
   * poll's `?: 0L`.
   */
  fun combineLiveMetrics(
      cpuPercent: Double,
      memTotal: Long,
      memUsed: Long,
      memBuffcache: Long?,
  ): LiveMetrics = LiveMetrics(
      cpuPercent = cpuPercent,
      memTotalGb = memTotal.bytesToGib(),
      memUsedGb = memUsed.bytesToGib(),
      memBuffGb = (memBuffcache ?: 0L).bytesToGib(),
  )
  ```
  Then replace the body of `toLiveMetrics` to delegate (proves single source of math):
  ```kotlin
  fun GetMetricsQuery.Data.toLiveMetrics(): LiveMetrics {
      val m = metrics
      return combineLiveMetrics(
          cpuPercent = m?.cpu?.percentTotal ?: 0.0,
          memTotal = m?.memory?.total ?: 0L,
          memUsed = m?.memory?.used ?: 0L,
          memBuffcache = m?.memory?.buffcache,
      )
  }
  ```

- [ ] **Step 4: Run — expect PASS (and toLiveMetrics still compiles for its poll caller)**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests "*.LiveMetricsMappingTest"
  ```
  Expected: PASS.

- [ ] **Step 5: Add the cpu/mem frame extractors + temperature/docker mappers + enum mapping**
  Add the generated-type imports near the existing `SystemMetricsNetworkSubscription` import, plus domain-model imports. `percentTotal` is already `Double`; `total/used` are `Long`, `buffcache` is `Long?`. The temperature mappers build a `TempSummarySample` and delegate to the shared `toTemperature`. The docker mapper drops `netIO`/`blockIO` (out of scope). The G-enum→domain `toDomain()` handles `UNKNOWN__`.
  ```kotlin
  import io.github.nofuturekid.nova.graphql.SystemMetricsCpuSubscription
  import io.github.nofuturekid.nova.graphql.SystemMetricsMemorySubscription
  import io.github.nofuturekid.nova.graphql.SystemMetricsTemperatureSubscription
  import io.github.nofuturekid.nova.graphql.DockerContainerStatsSubscription
  import io.github.nofuturekid.nova.graphql.type.TemperatureUnit as GTemperatureUnit
  import io.github.nofuturekid.nova.data.model.ContainerLiveStats
  import io.github.nofuturekid.nova.data.model.Temperature
  import io.github.nofuturekid.nova.data.model.TempSummarySample
  import io.github.nofuturekid.nova.data.model.TemperatureUnit
  import io.github.nofuturekid.nova.data.model.toTemperature
  ```
  Append in the subscription-mapper area (alongside `toIfaceSamples`):
  ```kotlin
  // ── CPU + Memory subscriptions (frames fed into combineLiveMetrics) ──

  fun SystemMetricsCpuSubscription.Data.cpuPercentTotal(): Double =
      systemMetricsCpu.percentTotal

  /** (total, used, buffcache?) — buffcache is nullable on the live server. */
  fun SystemMetricsMemorySubscription.Data.toMemoryTriple(): Triple<Long, Long, Long?> =
      Triple(systemMetricsMemory.total, systemMetricsMemory.used, systemMetricsMemory.buffcache)

  // ── Temperature subscription + poll (shared mapper) ──────────────────

  fun SystemMetricsTemperatureSubscription.Data.toTemperature(): Temperature =
      toTemperature(systemMetricsTemperature?.summary?.toSample())

  fun GetMetricsQuery.Data.toTemperature(): Temperature =
      toTemperature(metrics?.temperature?.summary?.toSample())

  private fun SystemMetricsTemperatureSubscription.Summary.toSample(): TempSummarySample =
      TempSummarySample(
          average = average,
          unit = hottest?.current?.unit?.toDomain() ?: TemperatureUnit.Unknown,
          hottestName = hottest?.name,
          hottestValue = hottest?.current?.value,
          warningCount = warningCount,
          criticalCount = criticalCount,
      )

  private fun GetMetricsQuery.Summary.toSample(): TempSummarySample =
      TempSummarySample(
          average = average,
          unit = hottest?.current?.unit?.toDomain() ?: TemperatureUnit.Unknown,
          hottestName = hottest?.name,
          hottestValue = hottest?.current?.value,
          warningCount = warningCount,
          criticalCount = criticalCount,
      )

  private fun GTemperatureUnit.toDomain(): TemperatureUnit = when (this) {
      GTemperatureUnit.CELSIUS    -> TemperatureUnit.Celsius
      GTemperatureUnit.FAHRENHEIT -> TemperatureUnit.Fahrenheit
      GTemperatureUnit.KELVIN     -> TemperatureUnit.Kelvin
      GTemperatureUnit.RANKINE    -> TemperatureUnit.Rankine
      else                        -> TemperatureUnit.Unknown // UNKNOWN__
  }

  // ── Docker container stats subscription ──────────────────────────────

  /** One frame = one container. Maps to a (id -> stats) pair for the overlay
   *  accumulation. netIO/blockIO are intentionally dropped (out of scope —
   *  the overlay shows cpu% + memory only). */
  fun DockerContainerStatsSubscription.Data.toContainerLiveStat(): Pair<String, ContainerLiveStats> =
      dockerContainerStats.let { s ->
          s.id to ContainerLiveStats(
              cpuPercent = s.cpuPercent,
              memPercent = s.memPercent,
              memUsage = s.memUsage,
          )
      }
  ```
  Note: the generated nested accessor names for the extended `GetMetrics` (`GetMetricsQuery.Summary`, `GetMetricsQuery.Hottest`, `GetMetricsQuery.Current`) and the subscription (`SystemMetricsTemperatureSubscription.Summary`/`.Hottest`/`.Current`) depend on Apollo's flattening — read the regenerated `GetMetricsQuery.kt`/`SystemMetricsTemperatureSubscription.kt` and adjust the receiver/`metrics?.temperature?.summary` chain to the actual names if they differ. The mapping semantics stay.

- [ ] **Step 6: Write the failing temperature Apollo-mapper test**
  Build a `SystemMetricsTemperatureSubscription.Data` with (a) a null root and (b) a populated summary; assert null → UNKNOWN (no NPE) and populated carries unit + counts. Adjust nested constructor names to match codegen if needed.
  ```kotlin
  package io.github.nofuturekid.nova.data.api

  import io.github.nofuturekid.nova.data.model.Temperature
  import io.github.nofuturekid.nova.data.model.TemperatureUnit
  import io.github.nofuturekid.nova.graphql.SystemMetricsTemperatureSubscription
  import io.github.nofuturekid.nova.graphql.type.TemperatureUnit as GTemperatureUnit
  import org.junit.Assert.assertEquals
  import org.junit.Test

  class TemperatureMappingTest {
      @Test fun null_root_maps_to_unknown_sentinel() {
          // WHY: systemMetricsTemperature is NULLABLE on the live server. A frame
          // whose root is null must degrade to the no-data sentinel without NPE —
          // the card shows 'unavailable', it does not crash.
          val data = SystemMetricsTemperatureSubscription.Data(systemMetricsTemperature = null)
          assertEquals(Temperature.UNKNOWN, data.toTemperature())
      }

      @Test fun populated_frame_carries_unit_and_counts() {
          // WHY: the server unit (Fahrenheit) must be carried through, not
          // assumed Celsius; warning/critical counts must survive to drive the
          // card accent.
          val current = SystemMetricsTemperatureSubscription.Current(value = 58.0, unit = GTemperatureUnit.FAHRENHEIT)
          val hottest = SystemMetricsTemperatureSubscription.Hottest(name = "CPU Package", current = current)
          val summary = SystemMetricsTemperatureSubscription.Summary(
              average = 41.5, hottest = hottest, warningCount = 2, criticalCount = 1,
          )
          val data = SystemMetricsTemperatureSubscription.Data(
              systemMetricsTemperature = SystemMetricsTemperatureSubscription.SystemMetricsTemperature(summary = summary),
          )
          assertEquals(
              Temperature(
                  available = true, average = 41.5, unit = TemperatureUnit.Fahrenheit,
                  hottestName = "CPU Package", hottestValue = 58.0,
                  warningCount = 2, criticalCount = 1,
              ),
              data.toTemperature(),
          )
      }
  }
  ```

- [ ] **Step 7: Run the mapper tests — expect PASS (fix generated names if needed)**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests "*.LiveMetricsMappingTest" --tests "*.TemperatureMappingTest"
  ```
  Expected: PASS. If a generated nested-type name mismatch surfaces, read the generated source and correct the type names (semantics unchanged) until green.

- [ ] **Step 8: Commit the mappers + tests**
  ```bash
  git add app/src/main/kotlin/io/github/nofuturekid/nova/data/api/GraphQlMapper.kt \
          app/src/test/kotlin/io/github/nofuturekid/nova/data/api/LiveMetricsMappingTest.kt \
          app/src/test/kotlin/io/github/nofuturekid/nova/data/api/TemperatureMappingTest.kt
  git commit -m "feat(mapper): combineLiveMetrics (poll+live) + cpu/mem/temp/docker frame mappers

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 5: Repository — metrics combine + temperatureStream + dockerStatsStream (TDD)

Wire the three live streams. CPU+Mem combine via the production `combineMetricsStates` fold over two per-domain `DomainState` legs: it emits `Content` only when BOTH legs are `Content` **with matching server URLs**, surfaces `NoServer`/`Error` per precedence, and is `Loading` until both legs have produced a `Content` (CRITIQUE B3 — `combine` emits as soon as each leg emits ONCE, which may be `Loading`/`Error`; the card never shows a half-populated `LiveMetrics`). `metricsStream()` keeps its EXACT signature and wraps the live combine in `subscriptionOrPoll`, with the existing `GetMetrics` poll as the fallback. `temperatureStream()` is `subscriptionOrPoll(tempSub, tempPoll)`. `dockerStatsStream()` accumulates frames into a growing immutable `Map<id, ContainerLiveStats>` (no fallback — no query path). The test seam `metricsCombineForTest` drives the **real `combineMetricsStates`** over `DomainState` inputs (CRITIQUE B4 — the draft seam tested stdlib `combine` over raw `Double`/`Triple` and could not fail when the production precedence logic broke).

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/MetricsCombineTest.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/DockerStatsAccumulationTest.kt`

- [ ] **Step 1: Write the failing metrics-combine test (drives the real `combineMetricsStates`)**
  <!-- CRITIQUE B4 (VALID, Rule-9): the draft's metricsCombineForTest combined raw Double/Triple flows and only shared combineLiveMetrics, never combineMetricsStates' DomainState precedence. A broken NoServer/Error/Content precedence could not fail it. FIX: the seam now takes List<DomainState<Double>> + List<DomainState<Triple>>, routes through combine + the real combineMetricsStates, so this test pins BOTH the wait-for-both-Content behavior AND the precedence fold. -->
  Case 1: cpu Content(10) then Content(20), mem Loading then Content(8GiB,2GiB,1GiB). Output: first a `Loading` (mem still Loading while cpu Content), then a single `Content` pairing the LATEST cpu (20.0) once mem Content arrives. Case 2: precedence — a cpu `Error` with mem `Content` surfaces the Error; a `NoServer` on either leg dominates. Case 3: shared math through `combineLiveMetrics`.
  ```kotlin
  package io.github.nofuturekid.nova.data.repository

  import io.github.nofuturekid.nova.data.model.LiveMetrics
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.flow.toList
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Test

  @OptIn(ExperimentalCoroutinesApi::class)
  class MetricsCombineTest {
      private val GiB = 1_073_741_824L
      private val URL = "http://srv"

      // WHY (B3 + intent): combine() emits as soon as EACH leg emits once — that
      // first emission may be Loading/Error, NOT Content. The card must never
      // render a half-populated LiveMetrics, so combineMetricsStates yields
      // Content ONLY when both legs are Content (matching URL). Here mem starts
      // Loading: the first combined state is Loading, and Content(20,8,2,1)
      // appears only once mem's Content arrives — pairing the LATEST cpu (20).
      // A combinator that seeded a default would emit Content with mem=0 first
      // and fail this.
      @Test fun emits_content_only_when_both_legs_are_content() = runTest {
          val out = UnraidRepository.metricsCombineForTest(
              cpuFrames = listOf(
                  DomainState.Content(10.0, URL),
                  DomainState.Content(20.0, URL),
              ),
              memFrames = listOf(
                  DomainState.Loading,
                  DomainState.Content(Triple(8 * GiB, 2 * GiB, 1L * GiB), URL),
              ),
          ).toList()
          assertTrue("first combined state is Loading (mem not Content yet)",
              out.first() is DomainState.Loading)
          assertEquals(
              DomainState.Content(LiveMetrics(20.0, 8.0, 2.0, 1.0), URL),
              out.last(),
          )
      }

      // WHY: the DomainState precedence fold must be exactly NoServer-dominates,
      // then first-Error, then both-Content. An Error on one leg with Content on
      // the other surfaces the Error (the card shows the failure, not a stale
      // half-frame). NoServer on either leg dominates everything.
      @Test fun folds_domainstate_precedence_noserver_then_error_then_content() = runTest {
          val errorOut = UnraidRepository.metricsCombineForTest(
              cpuFrames = listOf(DomainState.Error("cpu ws down")),
              memFrames = listOf(DomainState.Content(Triple(4 * GiB, 1 * GiB, null), URL)),
          ).toList()
          assertEquals(DomainState.Error("cpu ws down"), errorOut.last())

          val noServerOut = UnraidRepository.metricsCombineForTest(
              cpuFrames = listOf(DomainState.Content(50.0, URL)),
              memFrames = listOf(DomainState.NoServer),
          ).toList()
          assertEquals(DomainState.NoServer, noServerOut.last())
      }

      // WHY: combined frames must route through combineLiveMetrics (binary GiB,
      // null-buffcache->0) so the live transport equals the poll transport.
      @Test fun maps_each_combined_content_via_shared_metrics_math() = runTest {
          val out = UnraidRepository.metricsCombineForTest(
              cpuFrames = listOf(DomainState.Content(50.0, URL)),
              memFrames = listOf(
                  DomainState.Content(Triple(4 * GiB, 1 * GiB, null), URL),
                  DomainState.Content(Triple(4 * GiB, 3 * GiB, 1L * GiB), URL),
              ),
          ).toList()
          assertEquals(
              DomainState.Content(LiveMetrics(50.0, 4.0, 1.0, 0.0), URL),
              out.dropLast(1).last(),
          )
          assertEquals(
              DomainState.Content(LiveMetrics(50.0, 4.0, 3.0, 1.0), URL),
              out.last(),
          )
      }
  }
  ```

- [ ] **Step 2: Write the failing docker-accumulation test**
  Pins accumulate-not-replace (after frame b, a is still present) and update-by-id (re-emitting a overwrites only a's entry), each emission a distinct immutable map tagged with baseUrl.
  ```kotlin
  package io.github.nofuturekid.nova.data.repository

  import io.github.nofuturekid.nova.data.model.ContainerLiveStats
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.flow.toList
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Test

  @OptIn(ExperimentalCoroutinesApi::class)
  class DockerStatsAccumulationTest {
      private fun stat(cpu: Double, mem: Double, usage: String) =
          ContainerLiveStats(cpuPercent = cpu, memPercent = mem, memUsage = usage)

      /** WHY: each subscription frame carries ONE container; the overlay must
       *  ACCUMULATE — after b arrives, a is still present. A regression to
       *  last-frame-only (the obvious `map { it.second }`) drops a and fails. */
      @Test fun accumulates_distinct_ids_across_frames() = runTest {
          val frames = listOf(
              "a" to stat(10.0, 20.0, "1GiB"),
              "b" to stat(30.0, 40.0, "2GiB"),
          )
          val out = UnraidRepository.dockerStatsStreamForTest("https://x", frames).toList()
          assertEquals(
              listOf(
                  DomainState.Content(mapOf("a" to stat(10.0, 20.0, "1GiB")), "https://x"),
                  DomainState.Content(
                      mapOf("a" to stat(10.0, 20.0, "1GiB"), "b" to stat(30.0, 40.0, "2GiB")),
                      "https://x",
                  ),
              ),
              out,
          )
      }

      /** WHY: a re-emitted id is an UPDATE, not a duplicate — the same container
       *  streams again each tick with fresh numbers. The latest value for a
       *  wins; b is untouched. Pins update-by-id semantics. */
      @Test fun reemitting_an_id_updates_in_place() = runTest {
          val frames = listOf(
              "a" to stat(10.0, 20.0, "1GiB"),
              "b" to stat(30.0, 40.0, "2GiB"),
              "a" to stat(55.0, 60.0, "3GiB"),
          )
          val out = UnraidRepository.dockerStatsStreamForTest("https://x", frames).toList()
          assertEquals(
              DomainState.Content(
                  mapOf("a" to stat(55.0, 60.0, "3GiB"), "b" to stat(30.0, 40.0, "2GiB")),
                  "https://x",
              ),
              out.last(),
          )
          assertEquals(3, out.size)
      }
  }
  ```

- [ ] **Step 3: Run — expect FAIL (seams unresolved)**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests "*.MetricsCombineTest" --tests "*DockerStatsAccumulationTest"
  ```
  Expected: FAIL.

- [ ] **Step 4: Add the repository imports + companion test seams**
  Add imports near the existing `SystemMetricsNetworkSubscription` import and the flow imports:
  ```kotlin
  import io.github.nofuturekid.nova.graphql.SystemMetricsCpuSubscription
  import io.github.nofuturekid.nova.graphql.SystemMetricsMemorySubscription
  import io.github.nofuturekid.nova.graphql.SystemMetricsTemperatureSubscription
  import io.github.nofuturekid.nova.graphql.DockerContainerStatsSubscription
  import io.github.nofuturekid.nova.data.model.ContainerLiveStats
  import io.github.nofuturekid.nova.data.model.LiveMetrics
  import io.github.nofuturekid.nova.data.model.Temperature
  import io.github.nofuturekid.nova.data.api.combineLiveMetrics
  import io.github.nofuturekid.nova.data.api.cpuPercentTotal
  import io.github.nofuturekid.nova.data.api.toMemoryTriple
  import io.github.nofuturekid.nova.data.api.toContainerLiveStat
  import io.github.nofuturekid.nova.data.api.toTemperature
  import kotlinx.coroutines.flow.asFlow
  import kotlinx.coroutines.flow.combine
  import kotlinx.coroutines.flow.filter
  import kotlinx.coroutines.flow.scan
  ```
  Add the test seams to the companion object after `subscriptionStreamForTest`. The metrics seam drives the SAME `combine` + **`combineMetricsStates`** the production stream uses; the docker seam drives the SAME `accumulateStats`.
  <!-- CRITIQUE B4 fix: metricsCombineForTest now routes DomainState legs through the real combineMetricsStates, so the precedence fold is exercised by the test. combineMetricsStates is referenced here but defined as a member in Step 6; since both are in the same class/companion compilation unit this resolves — the seam calls the instance-independent fold via a companion-level copy is NOT used. Instead combineMetricsStates is made a companion function (Step 6) so the seam can call it directly. -->
  ```kotlin
  /**
   * Test-only seam for the cpu+mem combine -> LiveMetrics path (mirrors
   * [subscriptionStreamForTest]). Drives the SAME `combine` + [combineMetricsStates]
   * the production [liveMetricsStream] uses, over per-domain [DomainState] legs,
   * so a unit test pins BOTH the documented Content-only-when-both-Content
   * behaviour AND the NoServer/Error/Loading precedence fold (Rule 9) without
   * Apollo or a live WS. Same-module `internal`.
   */
  internal fun metricsCombineForTest(
      cpuFrames: List<DomainState<Double>>,
      memFrames: List<DomainState<Triple<Long, Long, Long?>>>,
  ): kotlinx.coroutines.flow.Flow<DomainState<LiveMetrics>> =
      combine(cpuFrames.asFlow(), memFrames.asFlow()) { c, m -> combineMetricsStates(c, m) }

  /**
   * The cpu%+mem-triple -> LiveMetrics DomainState fold, as a companion function
   * so both production [liveMetricsStream] and [metricsCombineForTest] call the
   * SAME body (no drift). NoServer on either leg dominates; otherwise the first
   * Error surfaces; Content only when BOTH legs are Content AND their server
   * URLs match (a cross-server pairing during a switch must not emit a Content
   * tagged with one leg's stale URL); anything else is Loading.
   */
  internal fun combineMetricsStates(
      cpu: DomainState<Double>,
      mem: DomainState<Triple<Long, Long, Long?>>,
  ): DomainState<LiveMetrics> = when {
      cpu is DomainState.NoServer || mem is DomainState.NoServer -> DomainState.NoServer
      cpu is DomainState.Error -> cpu
      mem is DomainState.Error -> mem
      cpu is DomainState.Content && mem is DomainState.Content && cpu.serverBaseUrl == mem.serverBaseUrl ->
          DomainState.Content(
              combineLiveMetrics(cpu.value, mem.value.first, mem.value.second, mem.value.third),
              cpu.serverBaseUrl,
          )
      else -> DomainState.Loading
  }

  /**
   * Pure frame-accumulation seam for the docker-stats overlay. Each frame
   * carries ONE container (id + stats); the overlay is the GROWING map across
   * frames (update/insert by id). Folded as an IMMUTABLE copy per step
   * (`acc + (id to stats)`) so every emission is a distinct map instance — a
   * shared MutableMap would make StateFlow/Compose miss updates. Returns the
   * running snapshots (one per frame), oldest first.
   */
  internal fun accumulateStats(
      frames: List<Pair<String, ContainerLiveStats>>,
  ): List<Map<String, ContainerLiveStats>> {
      val snapshots = mutableListOf<Map<String, ContainerLiveStats>>()
      var acc = emptyMap<String, ContainerLiveStats>()
      for ((id, stats) in frames) {
          acc = acc + (id to stats)
          snapshots += acc
      }
      return snapshots
  }

  /**
   * Test-only seam mirroring [subscriptionStreamForTest] for the docker-stats
   * accumulation. Emits one [DomainState.Content] per accumulated snapshot,
   * tagged with [baseUrl], then completes. Drives the SAME [accumulateStats]
   * the production [dockerStatsStream] uses.
   */
  internal fun dockerStatsStreamForTest(
      baseUrl: String,
      frames: List<Pair<String, ContainerLiveStats>>,
  ): kotlinx.coroutines.flow.Flow<DomainState<Map<String, ContainerLiveStats>>> =
      kotlinx.coroutines.flow.flow {
          for (snapshot in accumulateStats(frames)) emit(DomainState.Content(snapshot, baseUrl))
      }
  ```

- [ ] **Step 5: Run — expect PASS for the seam tests**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests "*.MetricsCombineTest" --tests "*DockerStatsAccumulationTest"
  ```
  Expected: PASS.

- [ ] **Step 6: Add the production live metrics stream + rewrite `metricsStream`**
  Add `liveMetricsStream()` near `networkThroughputStream` (after line 432). It calls the companion `combineMetricsStates` (defined in Step 4) so production and the seam share one fold. Rewrite `metricsStream` to wrap the live combine in `subscriptionOrPoll` with the existing `GetMetrics` poll as fallback — keeping the EXACT signature so MainViewModel/OverviewTab need no change. Reuse `SUB_FALLBACK_GRACE_MS` (do not introduce a second grace constant — Rule 7).
  <!-- CRITIQUE M1 (partially valid): combine of two flatMapLatest legs can transiently pair server-A cpu with server-B mem during a switch. combineMetricsStates now requires cpu.serverBaseUrl == mem.serverBaseUrl before emitting Content, so a mismatched pairing yields Loading instead of a Content tagged with a stale URL. resetIfForeignServer in the VM remains the backstop. -->
  ```kotlin
  /**
   * Live CPU+memory from the `systemMetricsCpu` + `systemMetricsMemory`
   * subscriptions, combined into the existing [LiveMetrics] model so both
   * Overview cards and [metricsStream]'s consumers stay unchanged. Each inner
   * [subscriptionStream] keys on the active server and tags Content with the
   * base URL; [combine] re-evaluates [combineMetricsStates] on every leg
   * emission, which yields Content only when BOTH legs are Content with the
   * SAME server URL (no half-populated, no cross-server card). Used as the
   * PRIMARY transport by [metricsStream], with the GetMetrics poll as the
   * [subscriptionOrPoll] fallback.
   */
  private fun liveMetricsStream(): Flow<DomainState<LiveMetrics>> {
      val cpu = subscriptionStream(SystemMetricsCpuSubscription()) { it.cpuPercentTotal() }
      val mem = subscriptionStream(SystemMetricsMemorySubscription()) { it.toMemoryTriple() }
      return combine(cpu, mem) { c, m -> combineMetricsStates(c, m) }
  }
  ```
  Replace `metricsStream` (lines 276-279, keeping the same signature):
  ```kotlin
  fun metricsStream(intervalMs: Long = POLL_METRICS_MS): Flow<DomainState<LiveMetrics>> {
      val poll = domainStream(intervalMs) { client, baseUrl ->
          fetch(client, GetMetricsQuery()) { data -> data.toLiveMetrics() }.withBaseUrl(baseUrl)
      }
      return subscriptionOrPoll(liveMetricsStream(), poll, SUB_FALLBACK_GRACE_MS)
  }
  ```

- [ ] **Step 7: Add `temperatureStream()` + its poll**
  Subscription primary, GetMetrics poll fallback (temperature has a query path → ADR-0026 mandates fallback).
  ```kotlin
  /**
   * Live temperature from `systemMetricsTemperature` with a poll fallback.
   *
   * Unlike [networkThroughputStream] (no query path → no fallback), temperature
   * IS exposed by the `GetMetrics` query, so ADR-0026 mandates a fallback: the
   * subscription is primary and degrades to the 2 s `GetMetrics` poll on
   * sustained WS failure (or if no first frame arrives within the grace window),
   * returning to the subscription when it recovers — so the card never goes
   * blank on a transient WS drop. The nullable root is tolerated by
   * [toTemperature] on both paths. Gated by the caller via [gatedStream].
   */
  fun temperatureStream(): Flow<DomainState<Temperature>> =
      subscriptionOrPoll(
          sub = subscriptionStream(SystemMetricsTemperatureSubscription()) { it.toTemperature() },
          poll = metricsTemperaturePoll(),
          graceMs = SUB_FALLBACK_GRACE_MS,
      )

  private fun metricsTemperaturePoll(intervalMs: Long = POLL_METRICS_MS): Flow<DomainState<Temperature>> =
      domainStream(intervalMs) { client, baseUrl ->
          fetch(client, GetMetricsQuery()) { data -> data.toTemperature() }.withBaseUrl(baseUrl)
      }
  ```

- [ ] **Step 8: Add `dockerStatsStream()`**
  Subscribe, `scan` the Content frames into the growing immutable map (same `acc + (id to stats)` as the pure seam); NoServer/Error/Loading reset the accumulation (a server switch or WS failure must not show the old server's stats). The trailing `.filter` drops the scan seed so the first real emission is the first frame's Content. No poll fallback (no query path).
  ```kotlin
  /**
   * Live per-container stats from the `dockerContainerStats` subscription.
   *
   * Each frame carries ONE container (id + stats). We ACCUMULATE frames into a
   * growing immutable `Map<containerId, ContainerLiveStats>` (update/insert by
   * id) and emit the growing map — the same accumulation pinned by
   * [accumulateStats]/[dockerStatsStreamForTest]. There is NO poll fallback:
   * the DockerContainer query exposes no stats fields, so on WS failure the
   * overlay simply stops growing and rows omit live stats (honest 'no live
   * stats', same stance as the network balloon). Gated to the Docker surfaces
   * by the caller (MainViewModel.dockerGate via gatedStream).
   */
  fun dockerStatsStream(): Flow<DomainState<Map<String, ContainerLiveStats>>> =
      subscriptionStream(DockerContainerStatsSubscription()) { data ->
          data.toContainerLiveStat()
      }.scan<DomainState<Pair<String, ContainerLiveStats>>, DomainState<Map<String, ContainerLiveStats>>>(
          DomainState.Loading,
      ) { acc, frame ->
          when (frame) {
              is DomainState.Content<Pair<String, ContainerLiveStats>> -> {
                  val prev = (acc as? DomainState.Content<Map<String, ContainerLiveStats>>)?.value
                      ?: emptyMap()
                  DomainState.Content(prev + frame.value, frame.serverBaseUrl)
              }
              // NoServer/Error/Loading reset the accumulation to that state — a
              // server switch or WS failure must not keep showing the old
              // server's accumulated stats. Safe covariant cast: these are all
              // DomainState<Nothing>.
              else -> @Suppress("UNCHECKED_CAST") (frame as DomainState<Map<String, ContainerLiveStats>>)
          }
      }.filter { it !is DomainState.Loading }
  ```
  Note: if Apollo makes `cpuPercent`/`memPercent` nullable `Double?` despite `Float!`, coalesce `?: 0.0` in `toContainerLiveStat` (match `toLiveMetrics`'s defensive style).

- [ ] **Step 9: Run the full unit suite — expect PASS, signatures stable**
  Confirms `metricsStream`'s signature is unchanged (MainViewModel/OverviewTab compile without edits) and no regression in existing repository/mapper tests.
  ```bash
  ./gradlew :app:testDirectDebugUnitTest
  ```
  Expected: PASS.

- [ ] **Step 10: Commit the streams + combine/accumulation tests**
  ```bash
  git add app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt \
          app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/MetricsCombineTest.kt \
          app/src/test/kotlin/io/github/nofuturekid/nova/data/repository/DockerStatsAccumulationTest.kt
  git commit -m "feat(repo): live cpu+mem combine (subscriptionOrPoll), temperatureStream, dockerStatsStream

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 6: ViewModel wiring — temperatureState, dockerStatsState, dockerLiveStats overlay, joinContainerStats (TDD for the join)

CPU/Mem need NO ViewModel change (the transport swap is invisible because `metricsStream` kept its signature). Add gated `temperatureState` (`overviewOnly()`, same gate as metrics) and `dockerStatsState` (`dockerGate()`, so the stats socket and docker poll come and go together). Expose a derived **`dockerLiveStats: StateFlow<Map<String, ContainerLiveStats>>`** — the unwrapped overlay map (empty when not Content) that the Docker UI consumes. The overlay/join itself is the high-risk part: a pure `joinContainerStats` companion seam that LEFT-joins so it never drops un-stat'd containers and preserves the polled list order. It is invoked from `DockerContent` (Task 7) over `dockerLiveStats`, so it is load-bearing, not dead code.

<!-- CRITIQUE B1 (VALID): the draft tested joinContainerStats but then did stats[c.id] inline in the Composable, orphaning the tested function (Rule-9 violation: a test pinning logic nothing calls). FIX: joinContainerStats is now the single overlay function, CALLED by DockerContent in Task 7 over the VM-exposed dockerLiveStats map. -->
<!-- DEVIATION from spec §C literal "combine(dockerState, dockerStatsState) in MainViewModel returning paired data": changing DockerTab's `state` param type from DomainState<List<Container>> to a paired type would ripple to its Loading/NoServer/Error handling AND to MainScreen lines 294/325 which read dockerState as Content<List<Container>>. Rule 2/3 (simplest, surgical): keep dockerState byte-identical, expose the overlay as a SEPARATE StateFlow (dockerLiveStats), and apply the tested joinContainerStats inside DockerContent. The join still lives in the VM companion and is unit-tested; only the call site is the Composable. This satisfies the intent (decoupled transports, tested left-join, preserved order) with minimal surgery. -->

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt`
- Test: `app/src/test/kotlin/io/github/nofuturekid/nova/ui/screens/main/ContainerStatsJoinTest.kt`

- [ ] **Step 1: Write the failing join test**
  Three tests, each encoding WHY: (1) a container with NO stats entry is KEPT with null stats (left-join — must not drop); (2) output order == polled list order (not the stats map's iteration order); (3) a present id gets its stats attached. The `Container` fixture matches the verified constructor (`id, name, image, status, autoStart, iconColorHex, iconUrl, cpu, memMb, ports: List<String>, volumes: List<String>, updateStatus, webUiUrl, networkIp`).
  ```kotlin
  package io.github.nofuturekid.nova.ui.screens.main

  import io.github.nofuturekid.nova.data.model.Container
  import io.github.nofuturekid.nova.data.model.ContainerLiveStats
  import io.github.nofuturekid.nova.data.model.ContainerStatus
  import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Test

  class ContainerStatsJoinTest {
      private fun container(id: String, name: String = id) = Container(
          id = id, name = name, image = "img", status = ContainerStatus.Running,
          autoStart = false, iconColorHex = null, iconUrl = null, cpu = 0.0, memMb = 0,
          ports = emptyList(), volumes = emptyList(),
          updateStatus = ContainerUpdateStatus.Unknown, webUiUrl = null, networkIp = null,
      )
      private fun stat(cpu: Double) = ContainerLiveStats(cpu, cpu, "${cpu}GiB")

      /** WHY (hard constraint): a container the stats stream hasn't sent a frame
       *  for yet MUST still appear — with null stats — never be dropped. A
       *  right-join over the stats map (the easy mistake) would drop 'b'. */
      @Test fun keeps_containers_without_a_stats_frame() {
          val containers = listOf(container("a"), container("b"))
          val joined = MainViewModel.joinContainerStats(containers, mapOf("a" to stat(10.0)))
          assertEquals(listOf("a", "b"), joined.map { it.first.id })
          assertEquals(stat(10.0), joined.first { it.first.id == "a" }.second)
          assertNull("b has no frame yet -> null stats, still present",
              joined.first { it.first.id == "b" }.second)
      }

      /** WHY: rows must render in the POLLED order (the list the user sees), not
       *  the stats map's iteration order. */
      @Test fun preserves_polled_list_order() {
          val containers = listOf(container("x"), container("y"), container("z"))
          val stats = mapOf("z" to stat(3.0), "x" to stat(1.0)) // different order, y absent
          val joined = MainViewModel.joinContainerStats(containers, stats)
          assertEquals(listOf("x", "y", "z"), joined.map { it.first.id })
      }

      /** WHY: the whole point — a present id gets its live stats attached. */
      @Test fun attaches_stats_by_id() {
          val joined = MainViewModel.joinContainerStats(
              listOf(container("a")), mapOf("a" to stat(42.0)),
          )
          assertEquals(stat(42.0), joined.single().second)
      }
  }
  ```

- [ ] **Step 2: Run — expect FAIL (joinContainerStats undefined)**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests "*ContainerStatsJoinTest"
  ```
  Expected: FAIL.

- [ ] **Step 3: Add `joinContainerStats` to the MainViewModel companion**
  Next to `resetIfForeignServer` (line 474). LEFT-join: iterate the polled containers (preserving order), attach `stats[c.id]` (null when absent). Add the `ContainerLiveStats` import.
  ```kotlin
  /**
   * Overlay live per-container [stats] onto the polled [containers] BY ID,
   * WITHOUT mutating [Container] (keeps the poll and WS transports decoupled,
   * spec §C). A LEFT join: every polled container is kept in its original order;
   * a container with no stats frame yet (or after a WS failure) gets `null`
   * stats so the row simply omits live numbers (no zero noise). The stats map's
   * own iteration order is irrelevant — output order is the polled list's order.
   * Called by DockerContent over [dockerLiveStats]; unit-tested in
   * ContainerStatsJoinTest.
   */
  fun joinContainerStats(
      containers: List<Container>,
      stats: Map<String, ContainerLiveStats>,
  ): List<Pair<Container, ContainerLiveStats?>> =
      containers.map { c -> c to stats[c.id] }
  ```

- [ ] **Step 4: Run — expect PASS**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest --tests "*ContainerStatsJoinTest"
  ```
  Expected: PASS.

- [ ] **Step 5: Expose the gated states + the derived overlay map**
  Add `temperatureState` after `networkThroughputState` (line 219, same `overviewOnly()` gate), `dockerStatsState` after `dockerState` (line 225, existing `dockerGate()`), and the derived `dockerLiveStats` (unwrapped map for the UI). Add the imports.
  ```kotlin
  import io.github.nofuturekid.nova.data.model.Temperature
  import io.github.nofuturekid.nova.data.model.ContainerLiveStats
  import kotlinx.coroutines.flow.map
  import kotlinx.coroutines.flow.SharingStarted
  import kotlinx.coroutines.flow.stateIn
  ```
  (Most of these flow imports already exist in `MainViewModel.kt` — only add the ones missing; do not duplicate.)
  ```kotlin
  val temperatureState: StateFlow<DomainState<Temperature>> =
      gatedStream(overviewOnly(), unraid.temperatureStream())

  val dockerStatsState: StateFlow<DomainState<Map<String, ContainerLiveStats>>> =
      gatedStream(dockerGate(), unraid.dockerStatsStream())

  /** The live per-container overlay, unwrapped to a plain map for the Docker UI
   *  (empty unless dockerStatsState is Content). DockerContent overlays this
   *  onto the polled container list via [joinContainerStats]. Kept SEPARATE from
   *  [dockerState] so the poll transport stays byte-identical (spec §C). */
  val dockerLiveStats: StateFlow<Map<String, ContainerLiveStats>> =
      dockerStatsState
          .map { (it as? DomainState.Content)?.value.orEmpty() }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
  ```

- [ ] **Step 6: Run the full suite — expect PASS**
  ```bash
  ./gradlew :app:testDirectDebugUnitTest
  ```
  Expected: PASS.

- [ ] **Step 7: Commit the VM wiring + join test**
  ```bash
  git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainViewModel.kt \
          app/src/test/kotlin/io/github/nofuturekid/nova/ui/screens/main/ContainerStatsJoinTest.kt
  git commit -m "feat(vm): gated temperatureState + dockerStatsState + dockerLiveStats overlay + joinContainerStats

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

---

### Task 7: UI — Temperature StatCard + DockerTab live rows (via joinContainerStats) + MainScreen wiring

Thread the new states through `MainScreen` into `OverviewTab` (Temperature `StatCard` with M3 warn/critical accent escalation, mirroring the CPU card's `if (cpuPercent>80) t.warn else t.accent` idiom at line 251) and `DockerTab` (live cpu%/mem per row from the overlay, omitted when no frame). `dockerState`'s type stays byte-for-byte unchanged (the separate-map choice); the overlay arrives as `stats: Map<String, ContainerLiveStats>` and `DockerContent` applies `MainViewModel.joinContainerStats(filtered, stats)` to produce the ordered `List<Pair<Container, ContainerLiveStats?>>` it iterates. No automated UI test — Compose rendering is gated by the maintainer's on-device acceptance (ADR-0027 Tier 3), as the network StatCard shipped. The load-bearing logic is already unit-pinned (Tasks 2–6). Verified: `UC.Thermo` exists (`UnraidIcons.kt` line 87, `Icons.Outlined.Thermostat`); theme tokens `t.danger`/`t.warn`/`t.accent`/`t.muted`/`t.info`/`t.text` all exist (`Color.kt` lines 12-18); `StatCard(iconColor, icon, label, value, sub, series, seriesColor, max)` (line 352); there are 4 `ContainerRow(...)` call sites (DockerTab lines 118, 154, 160, 166).

**Files:**
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/overview/OverviewTab.kt`
- Modify: `app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/docker/DockerTab.kt`

- [ ] **Step 1: Collect the new states in MainScreen and thread them**
  Alongside the existing collectors (lines 106-111). Do NOT change how `dockerState` is passed (separate-map design keeps it intact).
  ```kotlin
  val temperatureState by vm.temperatureState.collectAsState()
  val dockerLiveStats by vm.dockerLiveStats.collectAsState()
  ```
  At the `OverviewTab(...)` call (line 219, after the `networkThroughput` arg at line 227):
  ```kotlin
  temperature = (temperatureState as? DomainState.Content)?.value,
  ```
  At the `DockerTab(...)` call (line 276):
  ```kotlin
  stats = dockerLiveStats,
  ```

- [ ] **Step 2: Add the Temperature StatCard to OverviewTab**
  Add `temperature: Temperature? = null` to `OverviewTab(...)` (line 63) and `OverviewContent(...)` (line 104), forward it at the `OverviewContent(...)` call (line 100), add a `tempSeries` sparkline buffer + a `LaunchedEffect(temperature)` pushing the average only when available, and add the StatCard item after the Network card (line 283). Accent escalates `t.danger` (M3 error role) for critical, `t.warn` for warning, else `t.accent`. Unavailable → em-dash + "live unavailable". Add a `unitSymbol` helper.
  ```kotlin
  import io.github.nofuturekid.nova.data.model.Temperature
  import io.github.nofuturekid.nova.data.model.TemperatureUnit
  ```
  ```kotlin
  // OverviewTab(...) signature: add after `networkThroughput: NetworkThroughput? = null,`
  temperature: Temperature? = null,
  // OverviewTab body: forward it at the OverviewContent(...) call, adding
  // `temperature` as the new trailing arg:
  OverviewContent(info, metrics, array, containers, vms, server, networkThroughput, temperature)
  // OverviewContent(...) signature: add the matching trailing param
  temperature: Temperature? = null,
  ```
  ```kotlin
  // near the other series buffers (cpuSeries line 126, netSeries line 131):
  val tempSeries = remember { mutableStateOf(List(40) { 0f }) }
  // near the other LaunchedEffects (line 135/140):
  LaunchedEffect(temperature) {
      val v = temperature?.takeIf { it.available }?.average?.toFloat() ?: 0f
      tempSeries.value = tempSeries.value.drop(1) + v
  }
  ```
  ```kotlin
  // new item, placed right after the Network StatCard item (after line 283):
  item {
      val temp = temperature?.takeIf { it.available }
      val accent = when {
          temp == null -> t.muted
          temp.criticalCount > 0 -> t.danger   // M3 error role
          temp.warningCount > 0 -> t.warn
          else -> t.accent
      }
      StatCard(
          iconColor = t.muted,
          icon = { UC.Thermo(18.dp, t.muted) },
          label = "Temperature",
          value = temp?.let { "%.0f%s".format(it.average, unitSymbol(it.unit)) } ?: "—",
          sub = temp?.let {
              val counts = listOfNotNull(
                  if (it.criticalCount > 0) "${it.criticalCount} critical" else null,
                  if (it.warningCount > 0) "${it.warningCount} warning" else null,
              ).joinToString(" · ")
              val hottest = if (it.hottestName.isNotEmpty())
                  "${it.hottestName} ${"%.0f".format(it.hottestValue)}${unitSymbol(it.unit)}" else ""
              listOf(hottest, counts).filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
                  .ifEmpty { "all normal" }
          } ?: "live unavailable",
          series = tempSeries.value,
          seriesColor = accent,
          max = null,
      )
  }
  ```
  ```kotlin
  private fun unitSymbol(unit: TemperatureUnit): String = when (unit) {
      TemperatureUnit.Celsius    -> "°C"
      TemperatureUnit.Fahrenheit -> "°F"
      TemperatureUnit.Kelvin     -> "K"
      TemperatureUnit.Rankine    -> "°R"
      TemperatureUnit.Unknown    -> ""
  }
  ```

- [ ] **Step 3: Add live stats to DockerTab rows via `joinContainerStats`**
  Add `stats: Map<String, ContainerLiveStats> = emptyMap()` to `DockerTab(...)` (line 61) and `DockerContent(...)` (line 89); thread `stats = stats` at the `DockerContent(...)` call (line 75). Inside `DockerContent`, after computing `filtered` (line 100), build the overlay once: `val rows = MainViewModel.joinContainerStats(filtered, stats)`. Iterate `rows` (pairs) in the List and Grouped layouts so each row receives its `ContainerLiveStats?`; the Grid layout stays stats-free. For the Grouped layout, partition `rows` by `.first.status`. In `ContainerRow`, add `liveStats: ContainerLiveStats?` (3rd positional param) and render live cpu%/mem in the status Row only when non-null AND Running (guards stale entries after a stop). Use existing `labelSmall`/`t.muted` typography (Rule 13).
  <!-- CRITIQUE B2 (VALID): there are FOUR ContainerRow call sites (lines 118, 154, 160, 166), not one. All four must pass the stats arg. We route them through joinContainerStats so each site iterates Pair<Container, ContainerLiveStats?> and forwards `.second`. The Grid (ContainerGridTile, line 135) is correctly left untouched. -->
  ```kotlin
  import io.github.nofuturekid.nova.data.model.ContainerLiveStats
  import io.github.nofuturekid.nova.ui.screens.main.MainViewModel
  ```
  ```kotlin
  // DockerTab(...) param (after onStop / before onUpdateAll, any consistent slot):
  stats: Map<String, ContainerLiveStats> = emptyMap(),
  // DockerTab forwards it in the DomainState.Content branch:
  is DomainState.Content -> DockerContent(
      containers = state.value,
      serverBaseUrl = state.serverBaseUrl,
      view = view,
      stats = stats,
      onOpenContainer = onOpenContainer,
      onStart = onStart, onRestart = onRestart, onStop = onStop,
      onUpdateAll = onUpdateAll,
  )
  // DockerContent(...) param:
  stats: Map<String, ContainerLiveStats> = emptyMap(),
  ```
  ```kotlin
  // Inside DockerContent, immediately after `val filtered = ...` (line 100-104):
  val rows = MainViewModel.joinContainerStats(filtered, stats)
  ```
  ```kotlin
  // LIST layout — replace the items(filtered, key = { it.id }) block (line 117-119):
  items(rows, key = { it.first.id }) { (c, live) ->
      ContainerRow(c, serverBaseUrl, live, onOpenContainer, onStart, onRestart, onStop)
  }
  // GROUPED layout — replace lines 139-141 partitioning and the three items blocks:
  val running = rows.filter { it.first.status == ContainerStatus.Running }
  val paused  = rows.filter { it.first.status == ContainerStatus.Paused }
  val exited  = rows.filter { it.first.status == ContainerStatus.Exited }
  // ... Running section:
  items(running, key = { "r-${it.first.id}" }) { (c, live) ->
      ContainerRow(c, serverBaseUrl, live, onOpenContainer, onStart, onRestart, onStop)
  }
  // ... Paused section:
  items(paused, key = { "p-${it.first.id}" }) { (c, live) ->
      ContainerRow(c, serverBaseUrl, live, onOpenContainer, onStart, onRestart, onStop)
  }
  // ... Stopped section:
  items(exited, key = { "x-${it.first.id}" }) { (c, live) ->
      ContainerRow(c, serverBaseUrl, live, onOpenContainer, onStart, onRestart, onStop)
  }
  // GRID layout (line 134-136) — UNCHANGED: still iterates `filtered` and calls
  // ContainerGridTile(c, serverBaseUrl, onOpenContainer). Grid tiles show no live stats.
  ```
  ```kotlin
  // ContainerRow signature (line 239) — add liveStats as the 3rd param:
  private fun ContainerRow(
      c: Container,
      serverBaseUrl: String,
      liveStats: ContainerLiveStats?,
      onOpen: (Container) -> Unit,
      onStart: (Container) -> Unit,
      onRestart: (Container) -> Unit,
      onStop: (Container) -> Unit,
  ) {
  ```
  ```kotlin
  // Inside ContainerRow's Column, after the existing status/update Pill Row
  // (after line 281, still inside the weight(1f) Column):
  if (liveStats != null && c.status == ContainerStatus.Running) {
      Spacer(Modifier.height(3.dp))
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
          Text("%.1f%% CPU".format(liveStats.cpuPercent),
              color = t.muted, style = MaterialTheme.typography.labelSmall)
          Text(liveStats.memUsage,
              color = t.muted, style = MaterialTheme.typography.labelSmall,
              maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
  }
  ```

- [ ] **Step 4: Compile the debug variant — expect PASS**
  Catches signature/threading breakage end to end across all four `ContainerRow` sites + the OverviewTab card.
  ```bash
  ./gradlew :app:compileDirectDebugKotlin
  ```
  Expected: PASS.

- [ ] **Step 5: Commit the UI wiring**
  ```bash
  git add app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/main/MainScreen.kt \
          app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/overview/OverviewTab.kt \
          app/src/main/kotlin/io/github/nofuturekid/nova/ui/screens/docker/DockerTab.kt
  git commit -m "feat(ui): Overview Temperature StatCard + live per-container Docker rows (joinContainerStats)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

- [ ] **Step 6: Full-slice verification gate**
  Rule 4/12: loop until green. Success: TemperatureTest + TemperatureMappingTest + LiveMetricsMappingTest + MetricsCombineTest + DockerStatsAccumulationTest + ContainerStatsJoinTest + SubscriptionOrPollTest all pass; existing network/subscription/byte-format tests still pass; debug compiles. Do not report complete with a skipped or red test.
  ```bash
  ./gradlew :app:testDirectDebugUnitTest
  ```
  Expected: PASS.

---

### Task 8: Release tail — ADR-0043, ADR-0026 fixup, CHANGELOG, version bump, PR/CI/tag checklist

Delegated execution tail (Rule 14). NO product code. ADR-0043 must quote what the MERGED code actually does (read the final signatures first — `subscriptionOrPoll`, the field selections, the card description), not the spec proposal. Version bump keeps versionCode 108 (the never-shipped value already on this branch; 0.1.39 stable = 107; the broken v0.1.40-beta1 tag was built at 107). The broken beta1 tag is left untouched (non-destructive). Do NOT touch fdroiddata (owned by another session). Stable promotion stays user-only.

**Files:**
- Create: `docs/adr/0043-graphql-subscription-metrics-docker-rollout.md`
- Modify: `docs/adr/0026-graphql-subscriptions-hybrid.md`
- Modify: `CHANGELOG.md`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Write ADR-0043 (read merged code first)**
  Confirm the new ADR number (`ls docs/adr` → 0042 highest → 0043). Mirror ADR-0042's structure/voice (Status: Proposed; Context/Decision/Consequences{Positive,Negative,Trigger to revisit,Out of scope}/Alternatives/References). Record: roll the substrate to cpu/mem/temp/docker-stats reusing `subscriptionStream`/`buildWs`/`gatedStream` unchanged; the NEW `subscriptionOrPoll` (the ADR-0026 fallback mandate, finally implemented — metrics have a query path, unlike network); cpu+mem combine into `LiveMetrics`; docker stream-join overlay-by-id; supersedes ADR-0026 for these four domains; builds on ADR-0042. Quote the EXACT merged `subscriptionOrPoll` signature.
  ```bash
  ls docs/adr
  grep -n "subscriptionOrPoll" app/src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt
  ```
  ```markdown
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
    unit-tested combinator (quote the exact merged signature):

    ```kotlin
    internal fun <T> subscriptionOrPoll(
        sub: Flow<DomainState<T>>,
        poll: Flow<DomainState<T>>,
        graceMs: Long = SUB_FALLBACK_GRACE_MS,
    ): Flow<DomainState<T>>
    ```

    Subscription primary; `NoServer` forwarded immediately; degrades to `poll` on
    sustained `Error` or no first `Content` within `graceMs` (= `SUB_FALLBACK_GRACE_MS`,
    3× the 2 s metrics poll); returns to the sub on recovery (emitting the recovered
    frame directly). Poll collected only while in fallback.

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
  ```

- [ ] **Step 2: Self-check ADR-0043 skeleton + commit**
  Verify header/sections and that the quoted code matches the merged signatures.
  ```bash
  grep -nE '^#|^- \*\*Status|^- \*\*Date|^## ' docs/adr/0043-graphql-subscription-metrics-docker-rollout.md
  git add docs/adr/0043-graphql-subscription-metrics-docker-rollout.md
  git commit -m "docs(adr-0043): roll WS subscriptions out to cpu/mem/temperature/docker-stats

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

- [ ] **Step 3: Update ADR-0026 Status line + commit (read the exact line first)**
  <!-- CRITIQUE M5 (VALID): the draft guessed the OLD text. Verified actual line 3 below; the executor must Read it to confirm it has not drifted, then Edit the exact string. -->
  The verified current line 3 of `docs/adr/0026-graphql-subscriptions-hybrid.md` is:
  ```
  - **Status**: Superseded by ADR-0042 for network throughput (network pilot); metrics/docker/array still pending.
  ```
  Read the file first to confirm it is unchanged, then replace that exact line with:
  ```
  - **Status**: Superseded by ADR-0042 for network throughput, and by ADR-0043 for CPU/memory/temperature and docker-container stats; array & notifications still pending (server-side blocked, see amendment).
  ```
  ```bash
  grep -n 'ADR-0043' docs/adr/0026-graphql-subscriptions-hybrid.md && git diff --stat docs/adr/0026-graphql-subscriptions-hybrid.md
  git add docs/adr/0026-graphql-subscriptions-hybrid.md
  git commit -m "docs(adr-0026): note ADR-0043 supersede for cpu/mem/temp/docker

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

- [ ] **Step 4: Add plain-language CHANGELOG bullets under `## [Unreleased]`**
  JARGON BAN (ADR-0031): no "subscription", "WebSocket", "WS", "GraphQL", "combinator", "stream-join", "gatedStream". Symptoms only. The network bullet (under `### Added`) is the tone template; cpu/mem is a Changed (cards existed), temperature + docker are Added. Write these bullets under the existing `## [Unreleased]` heading (do NOT rename it yet — the rename happens in Step 6 as the single, final CHANGELOG end-state, CRITIQUE M6).
  ```markdown
  ## [Unreleased]

  ### Added
  - A new CPU-temperature card on the dashboard shows your server's current temperature live, with the hottest sensor highlighted; it turns amber or red if a sensor crosses a warning or critical threshold.
  - The Docker tab now shows live CPU and memory usage for each running container, updating in real time.

  ### Changed
  - The dashboard's CPU and Memory cards now update live, in real time, instead of refreshing every couple of seconds. If the live connection drops, they automatically fall back to the usual 2-second refresh so the cards never go blank.
  ```

- [ ] **Step 5: Scan CHANGELOG for jargon leaks + commit**
  A non-zero match is a defect (Rule 12) — fix before commit. Scan the `[Unreleased]` block (still named that at this step).
  ```bash
  awk '/^## \[Unreleased\]/{f=1; next} /^## \[/{f=0} f' CHANGELOG.md | grep -niE 'subscription|websocket|graphql|combinator|gatedstream|flatmaplatest|stream-join|\bWS\b' && echo 'JARGON FOUND — FIX' || echo 'clean'
  git add CHANGELOG.md
  git commit -m "docs(changelog): live CPU/mem/temperature cards + per-container docker stats

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

- [ ] **Step 6: Version bump (guard + bump) + CHANGELOG release rename (single pinned end-state)**
  <!-- CRITIQUE M6 (VALID): the draft contradicted itself on the CHANGELOG end-state (File-Structure said "rename Unreleased→beta2"; the body said "empty Unreleased above a beta2 heading"). PINNED end-state below: keep an EMPTY `## [Unreleased]` placeholder at the top, then a `## [0.1.40-beta2] - 2026-05-30` heading carrying the Added/Changed groups. The release-notes slicer matches the section by the version tag, so the beta2 heading must exist verbatim. -->
  versionCode REASONING (state verbatim in the commit body): Android requires versionCode to strictly increase and never reuse a code for a DIFFERENT artifact. 0.1.39 stable = 107. The broken v0.1.40-beta1 tag was built at a commit that still read 107/0.1.39 (the #200 squash omitted the release commit) — it never validly shipped as 108. This branch already carries 108 (never merged/tagged/distributed). So beta2 ships at 108 (>107, never-shipped). The broken tag is left untouched. GUARD: confirm no artifact shipped at 108 before committing; if one has, bump to 109.
  ```bash
  git tag --list 'v0.1.40*'; git log --all --oneline -- app/build.gradle.kts | head; grep -n 'versionCode' app/build.gradle.kts
  ```
  CHANGELOG end-state (apply exactly):
  ```markdown
  ## [Unreleased]

  ## [0.1.40-beta2] - 2026-05-30

  ### Added
  - A new CPU-temperature card on the dashboard shows your server's current temperature live, with the hottest sensor highlighted; it turns amber or red if a sensor crosses a warning or critical threshold.
  - The Docker tab now shows live CPU and memory usage for each running container, updating in real time.

  ### Changed
  - The dashboard's CPU and Memory cards now update live, in real time, instead of refreshing every couple of seconds. If the live connection drops, they automatically fall back to the usual 2-second refresh so the cards never go blank.
  ```
  ```bash
  # app/build.gradle.kts: keep `versionCode = 108`; change `versionName = "0.1.40-beta1"`
  #   to `versionName = "0.1.40-beta2"`.
  grep -nE 'versionCode|versionName' app/build.gradle.kts; grep -nE '^## \[(Unreleased|0\.1\.40-beta2)\]' CHANGELOG.md
  git add app/build.gradle.kts CHANGELOG.md
  git commit -m "release: 0.1.40-beta2 — live CPU/mem/temperature + per-container docker stats (versionCode 108)

versionCode stays 108: 0.1.39 stable = 107; the broken v0.1.40-beta1 tag was
built at a commit reading 107 (the #200 merge omitted the release commit) and
never validly shipped as 108; this branch carries 108 but it has never been
merged/tagged/distributed. beta2 reuses 108 (>107, never-shipped). The broken
v0.1.40-beta1 tag is left untouched in history.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  ```

- [ ] **Step 7: Release-checklist FOR THE RELEASE SUB-AGENT (not the planner)**
  The planner does NOT run git/gradle/builds for shipping. The release sub-agent runs, in order:
  1. PRE-FLIGHT: confirm all product + doc commits are on `feat/live-network-graph` (the current branch — schema/ops, models, mappers, repository, VM, UI, ADR-0043, ADR-0026, CHANGELOG, version bump). If any is missing, STOP — beta2 must be the complete big-bang (ADR-0030).
  2. LIVE-VERIFY (HTTP): `graphql-smoke` skill against the extended `GetMetrics` (cpu/memory/temperature query fields). Read-only; key via gitignored env only.
  3. LIVE-VERIFY (WS): read-only `graphql-transport-ws` probe of all four subscriptions (x-api-key in connectionPayload). Assert a usable first frame each (tolerate an initial null temperature). If any does not deliver, STOP — do not tag.
  4. PR to main via `gh` (body links ADR-0043, notes versionCode reasoning, ends with the Generated-with-Claude-Code line).
  5. WATCH CI to green (`gh pr checks --watch`); fix-forward on red, never merge red.
  6. SQUASH-MERGE on green, then verify the squashed main commit actually contains versionCode 108 / versionName 0.1.40-beta2 in build.gradle.kts BEFORE tagging (this is the exact check that would have caught the beta1 defect).
  7. CUT `v0.1.40-beta2` on the squashed main commit (leave v0.1.40-beta1 untouched); confirm the tagged tree reads 108/beta2.
  8. AWAIT MAINTAINER ON-DEVICE ACCEPTANCE of all live surfaces (ADR-0030 + ADR-0027 Tier 3). If any domain fails live: stop, diagnose, do NOT promote. STABLE PROMOTION IS USER-ONLY. Do NOT touch fdroiddata.
  ```bash
  # Example PR creation (release sub-agent); --head is the current branch:
  gh pr create --base main --head feat/live-network-graph \
    --title "feat: live CPU/mem/temperature + per-container docker stats (0.1.40-beta2)" \
    --body "Rolls the ADR-0042 WS subscription substrate out to CPU, memory, temperature, and docker container stats (ADR-0043). CPU/Mem/Temp degrade to the existing 2 s GetMetrics poll on WS failure; docker stats and the temperature card are new surfaces. Ships as 0.1.40-beta2, versionCode 108. Beta-first (ADR-0006); on-device acceptance (ADR-0027 Tier 3) is the promotion gate.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
  ```

<!-- Note on branch: the draft Task 8 referenced `feat/live-metrics-subscriptions`, but the actual working branch is `feat/live-network-graph` (verified via git status). Corrected in the pre-flight + PR command above. -->

---

Key reconciliations applied from the adversarial review (all verified against the real files): **B1/B2** — `joinContainerStats` is now load-bearing (called by `DockerContent` over the VM-exposed `dockerLiveStats`), and all four `ContainerRow` call sites are threaded; **B3** — `combineMetricsStates` KDoc/contract corrected (Content only when both legs are Content; Loading otherwise) and a precedence test added; **B4** — `metricsCombineForTest` now drives the real `combineMetricsStates` over `DomainState` legs so the precedence fold can actually fail a test (Rule 9); **B5** — `subscriptionOrPoll` forwards `NoServer` immediately with a dedicated test; **M1** — `combineMetricsStates` requires matching server URLs before emitting Content; **M2** — recovery emits the recovered Content directly (no fragile `transformLatest` re-fire); **M4** — the `available && unit==Unknown` partial frame is documented and tested; **M5** — the exact ADR-0026 status line is supplied with a Read-first instruction; **M6** — a single pinned CHANGELOG end-state. `subscriptionOrPoll`/`combineMetricsStates`/`joinContainerStats` signatures and the `Temperature`/`TemperatureUnit`/`ContainerLiveStats`/`TempSummarySample` field names are held identical across all tasks and the ADR.
