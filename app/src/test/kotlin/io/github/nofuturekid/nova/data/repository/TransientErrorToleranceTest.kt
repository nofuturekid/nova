package io.github.nofuturekid.nova.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Triage #21 — [UnraidRepository.domainStream]'s transient-error tolerance.
 *
 * Documented intent (UnraidRepository KDoc + `TRANSIENT_ERROR_TOLERANCE = 3`):
 * once a poll has produced a successful [DomainState.Content], a short burst
 * of fetch failures must NOT immediately tear the UI down to [DomainState.Error].
 * The last good Content is re-emitted for up to (tolerance − 1) consecutive
 * failures; only when failures REACH `TRANSIENT_ERROR_TOLERANCE` does the
 * Error surface. A single success resets the failure counter so the grace
 * window is per-burst, not lifetime.
 *
 * WHY this matters (Rule 9): Unraid GraphQL polls flap on Wi-Fi handoffs,
 * brief server load, and reverse-proxy hiccups. Without the grace window the
 * dashboard would strobe Content↔Error on every transient blip — exactly the
 * resilience defect triage #21 pins. This test asserts the *intent* (how many
 * failures are tolerated, that a success resets it), not just "an error can
 * appear".
 *
 * The test drives the REAL poll loop via the same-module `internal`
 * [UnraidRepository.Companion.pollDomainForTest] seam (mirroring the existing
 * `internal` unit-test entrypoint convention, e.g. `UpdateRepository.
 * parseVersion`). `pollDomainForTest` runs the identical production
 * `pollDomain` body — no re-derived copy — so this test genuinely fails if the
 * tolerance is removed/changed: e.g. drop the grace and it flaps to Error on
 * failure #1; widen/narrow `TRANSIENT_ERROR_TOLERANCE` and the failure#2 /
 * failure#3 assertions break.
 *
 * Collector style mirrors UpdateControllerTest / StaleCrossServerTest
 * (launch into a list, driven on a virtual scheduler). Because the poll loop
 * is infinite, the scheduler is stepped one poll interval at a time via
 * advanceTimeBy + runCurrent (advanceUntilIdle would never return). JUnit +
 * kotlinx-coroutines-test only, no new deps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransientErrorToleranceTest {

    private val good = DomainState.Content(value = "payload", serverBaseUrl = "http://srv.local")
    private val boom = DomainState.Error("transient network blip")

    /** Virtual poll interval — the loop `delay()`s this between iterations;
     *  advancing the test scheduler drives one iteration per step. */
    private val intervalMs = 1_000L

    @Test
    fun transientFailuresAreToleratedThenSurfaceThenResetOnSuccess() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))

        // The scripted fetch outcome for the *next* poll iteration. The test
        // flips this between scheduler advances to simulate a per-iteration
        // success/failure sequence through the real loop.
        val next = MutableStateFlow<DomainState<String>>(good)

        val emissions = mutableListOf<DomainState<String>>()
        val job = scope.launch {
            UnraidRepository.pollDomainForTest(intervalMs) { next.value }
                .collect { emissions += it }
        }

        // ── Iteration 1: a success → state is Content(payload).
        // WHY: a fresh successful poll must surface its payload; this also
        // arms the grace window (the loop now has a "last good Content").
        // NOTE: the poll loop is `while(true)`, so advanceUntilIdle() would
        // never return (a delay() is always scheduled). With the unconfined
        // test dispatcher `launch` runs eagerly to the first delay(), so
        // runCurrent() settles exactly the iteration-1 emission.
        scope.testScheduler.runCurrent()
        assertEquals(good, emissions.last())

        // ── Iteration 2: failure #1 (within tolerance) → still Content.
        // WHY: one blip after a good poll must NOT tear the UI to Error —
        // the last good Content is re-served. Fails (flaps to Error) if the
        // grace window is removed.
        next.value = boom
        scope.testScheduler.advanceTimeBy(intervalMs)
        scope.testScheduler.runCurrent()
        assertEquals(good, emissions.last())

        // ── Iteration 3: failure #2 (still within tolerance) → still Content.
        // WHY: pins the *width* of the grace window — the 2nd consecutive
        // failure is still tolerated (consecutiveErrors=2 < TOLERANCE=3).
        // Fails if TRANSIENT_ERROR_TOLERANCE is narrowed below 3.
        scope.testScheduler.advanceTimeBy(intervalMs)
        scope.testScheduler.runCurrent()
        assertEquals(good, emissions.last())

        // ── Iteration 4: failure #3 (REACHES tolerance) → Error surfaces.
        // WHY: this is the exhaustion boundary — once failures reach
        // TRANSIENT_ERROR_TOLERANCE the stale Content must be replaced by
        // the real Error so the user isn't shown indefinitely stale data.
        // Fails if the tolerance is widened (would still be Content here).
        scope.testScheduler.advanceTimeBy(intervalMs)
        scope.testScheduler.runCurrent()
        assertEquals(boom, emissions.last())

        // ── Iteration 5: a success → Content again (counter reset).
        // WHY: recovery must be immediate — a single good poll clears the
        // error state and re-arms the grace window.
        next.value = good
        scope.testScheduler.advanceTimeBy(intervalMs)
        scope.testScheduler.runCurrent()
        assertEquals(good, emissions.last())

        // ── Iteration 6: a single failure AFTER the reset → still Content.
        // WHY: proves the counter genuinely RESET on success (it is not a
        // lifetime budget). If success did not reset consecutiveErrors, this
        // 4th lifetime failure would already be >= tolerance and surface
        // Error — so this assertion fails if the reset regresses.
        next.value = boom
        scope.testScheduler.advanceTimeBy(intervalMs)
        scope.testScheduler.runCurrent()
        assertEquals(good, emissions.last())

        job.cancel()
    }
}
