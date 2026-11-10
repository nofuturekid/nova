package io.github.nofuturekid.nova.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L1 + M4 — the "fetch again now" request must not be lost while a fetch is in
 * flight (a SharedFlow without subscriber drops it), and pull-to-refresh must
 * actually refetch the visible data and report when it has arrived.
 * Driven through the production poll loop ([UnraidRepository.pollDomainForTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NudgeTest {
    private val interval = 60_000L

    // WHY: the user archives a notification while a poll is in flight; that poll returns
    // pre-archive data. The nudge must still cause one more fetch right after it.
    @Test fun nudgeDuringInFlightFetch_causesOneMoreFetch() = runTest {
        val nudge = Nudge()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        backgroundScope.launch {
            UnraidRepository.pollDomainForTest(interval, listOf(nudge)) {
                fetches++
                if (fetches == 1) gate.await()
                DomainState.Content("v$fetches", "u")
            }.collect { }
        }
        runCurrent()
        assertEquals(1, fetches)
        nudge.fire()            // nobody is waiting on the nudge: the fetch is in flight
        gate.complete(Unit)
        runCurrent()
        assertEquals("the nudge fired during the fetch must trigger a refetch", 2, fetches)
        advanceTimeBy(interval - 1)
        runCurrent()
        assertEquals("and only one, not a tight loop", 2, fetches)
    }

    @Test fun nudgeWhileWaiting_refetchesImmediately_andBurstsCollapse() = runTest {
        val nudge = Nudge()
        var fetches = 0
        backgroundScope.launch {
            UnraidRepository.pollDomainForTest(interval, listOf(nudge)) {
                fetches++; DomainState.Content("v", "u")
            }.collect { }
        }
        runCurrent()
        nudge.fire(); nudge.fire()
        runCurrent()
        assertEquals(2, fetches)
    }

    // WHY (M4): refreshAll must end when refreshed data has arrived, not before and not
    // after a long wait — the spinner is bound to this.
    @Test fun refresh_completesWhenTheRefetchFinished() = runTest {
        val refresh = Nudge()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        backgroundScope.launch {
            UnraidRepository.pollDomainForTest(interval, listOf(refresh)) {
                fetches++
                if (fetches == 2) gate.await()
                DomainState.Content("v$fetches", "u")
            }.collect { }
        }
        runCurrent()
        val gen = refresh.fire()
        runCurrent()
        assertEquals(2, fetches)
        assertNull(
            "refetch still in flight: refresh must not report done yet",
            withTimeoutOrNull(1_000) { refresh.awaitCompleted(gen) },
        )
        gate.complete(Unit)
        runCurrent()
        assertNotNull(withTimeoutOrNull(1_000) { refresh.awaitCompleted(gen) })
    }

    // WHY (M4): a failed refetch still ends the spinner (data "arrived or error").
    @Test fun refresh_completesOnError_too() = runTest {
        val refresh = Nudge()
        backgroundScope.launch {
            UnraidRepository.pollDomainForTest<String>(interval, listOf(refresh)) {
                DomainState.Error("down")
            }.collect { }
        }
        runCurrent()
        val gen = refresh.fire()
        runCurrent()
        assertNotNull(withTimeoutOrNull(1_000) { refresh.awaitCompleted(gen) })
    }
}
