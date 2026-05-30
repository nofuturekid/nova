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
}
