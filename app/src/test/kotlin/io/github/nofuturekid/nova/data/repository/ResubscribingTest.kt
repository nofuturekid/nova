package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.perServer
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.resubscribing
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.subscriptionOrPoll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 245 — [UnraidRepository.Companion.resubscribing] and its hook-up to
 * [subscriptionOrPoll].
 *
 * WHY (Rule 9): the Apollo WebSocket transport reconnects nothing by itself, so
 * before this loop a dropped, completed or silent subscription froze the card on
 * its last value (or left it on the slow poll) until the screen was left and
 * re-entered. These tests drive the REAL loop on the virtual scheduler with
 * scripted attempts, and pin each way an attempt can end, the backoff, and that
 * the Errors it emits take the cards to the poll fallback and the next Content
 * brings them back. Time is virtual only; nothing here reads a wall clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResubscribingTest {

    /** Runs [body] on a scope whose dispatcher is the virtual scheduler, and
     *  cancels it afterwards even when an assertion failed: the scripted streams
     *  and the poll loop never end by themselves, and a leaked one would keep
     *  runTest advancing virtual time forever. */
    private fun virtual(body: suspend TestScope.() -> Unit) = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        try {
            scope.body()
        } finally {
            scope.cancel()
        }
    }

    private val url = "http://srv"
    private val frameGapMs = 10L

    /** A healthy stream: a frame every 5 s (about the slowest real publisher), so
     *  the silence watchdog stays quiet. */
    private fun liveForever(value: String): Flow<DomainState<String>> = flow {
        while (true) { emit(s(value)); delay(5_000) }
    }

    /** A live frame, then (after a short gap, like a real socket) the given tail.
     *  Back-to-back emission would be conflated by the combinator's StateFlow
     *  before the S1 frame is ever seen, which no real socket does. */
    private fun liveThen(vararg tail: DomainState<String>): Flow<DomainState<String>> = flow {
        emit(s("S1"))
        delay(frameGapMs)
        for (t in tail) emit(t)
    }
    private fun s(v: String) = DomainState.Content(v, url)

    /** Scripted attempts: call N runs script N (the last script repeats), and the
     *  virtual time of every call is recorded. */
    private class Script(
        private val scheduler: TestCoroutineScheduler,
        private val scripts: List<() -> Flow<DomainState<String>>>,
    ) {
        val calls = mutableListOf<Long>()
        fun attempt(): Flow<DomainState<String>> {
            val i = calls.size
            calls += scheduler.currentTime
            return scripts[minOf(i, scripts.lastIndex)]()
        }
        val gaps: List<Long> get() = calls.zipWithNext { a, b -> b - a }
    }

    /** Output values with consecutive duplicates collapsed (the combinator may
     *  re-emit a recovered frame). */
    private fun values(out: List<DomainState<String>>): List<String> =
        out.map {
            when (it) {
                is DomainState.Content -> it.value
                is DomainState.Error -> "E:${it.message}"
                DomainState.Loading -> "loading"
                DomainState.NoServer -> "noserver"
            }
        }.fold(mutableListOf<String>()) { acc, v -> if (acc.lastOrNull() != v) acc += v; acc }

    private fun pollCounting(starts: () -> Unit): Flow<DomainState<String>> = flow {
        starts()
        var i = 0
        while (true) {
            emit(s("POLL$i"))
            i++
            delay(UnraidRepository.POLL_METRICS_MS)
        }
    }

    @Test
    fun constantsKeepSilenceAboveSlowestPublisherAndFirstRetryInsideGrace() {
        // WHY: temperature, the slowest live publisher, sends about every 5 s; a
        // healthy stream must never trip the watchdog (3x slack). The first retry
        // must land inside the grace window so a quick reconnect never shows the
        // poll. Fails if either constant is tuned past its reason.
        assertTrue(UnraidRepository.SUB_SILENCE_MS >= 3 * 5_000L)
        assertTrue(UnraidRepository.SUB_RETRY_BASE_MS < UnraidRepository.SUB_FALLBACK_GRACE_MS)
        assertTrue(UnraidRepository.SUB_RETRY_BASE_MS <= UnraidRepository.SUB_RETRY_MAX_MS)
    }

    @Test
    fun droppedSocketFallsBackToPollThenRecoversToPush() = runTest {
        // WHY: the reported bug. After a drop the card stayed frozen (or on the
        // poll forever). It must go to the poll after the grace window while the
        // loop keeps retrying with doubling backoff, and return to the live
        // stream, stopping the poll, as soon as a retry succeeds.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf(
                { liveThen(DomainState.Error("socket closed")) },
                { flowOf(DomainState.Error("refused")) },
                { flowOf(DomainState.Error("refused")) },
                { liveForever("S2") },
            ),
        )
        var pollStarts = 0
        val out = mutableListOf<DomainState<String>>()
        val job = launch {
            subscriptionOrPoll(
                resubscribing(script::attempt),
                pollCounting { pollStarts++ },
                UnraidRepository.SUB_FALLBACK_GRACE_MS,
            ).collect { out += it }
        }
        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()

        assertEquals(listOf("S1", "POLL0", "S2"), values(out))
        // Retries at +1 s, +2 s, +4 s after the failures (the first attempt ends
        // after its frame gap).
        val g = frameGapMs
        assertEquals(listOf(0L, g + 1_000L, g + 3_000L, g + 7_000L), script.calls)
        // The poll ran once and stopped when S2 arrived (no POLL after S2).
        assertEquals(1, pollStarts)
        assertEquals(s("S2"), out.last())
        job.cancel()
    }

    @Test
    fun quickReconnectStaysOnPush() = runTest {
        // WHY: a short drop that reconnects within the grace window must not
        // flicker the card onto the poll (that would hit the server with HTTP
        // queries on every blip). Fails if the first retry waits longer than the
        // grace window or the combinator collects the poll on a single Error.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf(
                { liveThen(DomainState.Error("socket closed")) },
                { liveForever("S2") },
            ),
        )
        var pollStarts = 0
        val out = mutableListOf<DomainState<String>>()
        val job = launch {
            subscriptionOrPoll(
                resubscribing(script::attempt),
                pollCounting { pollStarts++ },
                UnraidRepository.SUB_FALLBACK_GRACE_MS,
            ).collect { out += it }
        }
        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()

        assertEquals(0, pollStarts)
        assertEquals(listOf("S1", "S2"), values(out))
        assertEquals(listOf(0L, frameGapMs + 1_000L), script.calls)
        job.cancel()
    }

    @Test
    fun silentStreamAfterFirstFrameDegradesToPoll() = runTest {
        // WHY: a socket that stays open but stops delivering (half-open, server
        // stuck) produces no error at all, so the card froze on its last value
        // forever. The watchdog must replace the attempt after 15 s of silence,
        // and the card must reach the poll 6 s after that.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        var firstCancelled = false
        val script = Script(
            testScheduler,
            listOf(
                {
                    flow<DomainState<String>> { emit(s("S1")); awaitCancellation() }
                        .onCompletion { firstCancelled = true }
                },
                { flow { awaitCancellation() } },
            ),
        )
        var pollStarts = 0
        val out = mutableListOf<DomainState<String>>()
        val job = launch {
            subscriptionOrPoll(
                resubscribing(script::attempt),
                pollCounting { pollStarts++ },
                UnraidRepository.SUB_FALLBACK_GRACE_MS,
            ).collect { out += it }
        }
        testScheduler.advanceTimeBy(14_999)
        testScheduler.runCurrent()
        assertFalse("attempt must still run before the silence window ends", firstCancelled)
        assertEquals(0, pollStarts)

        testScheduler.advanceTimeBy(1)       // t = 15 s: silence detected
        testScheduler.runCurrent()
        assertTrue("silent attempt must be cancelled", firstCancelled)
        testScheduler.advanceTimeBy(5_999)   // t = 20.999 s: still inside the grace
        testScheduler.runCurrent()
        assertEquals(0, pollStarts)
        assertEquals(listOf("S1"), values(out))

        testScheduler.advanceTimeBy(1)       // t = 21 s: grace over
        testScheduler.runCurrent()
        assertEquals(1, pollStarts)
        assertEquals(s("POLL0"), out.last())
        // A new attempt was opened 1 s after the silence (t = 16 s).
        assertEquals(16_000L, script.calls[1])
        // Further silent attempts (Errors in the same degraded stretch) do not
        // restart the running poll.
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(1, pollStarts)
        job.cancel()
    }

    @Test
    fun completedStreamForwardsErrorAndResubscribes() = runTest {
        // WHY: a graphql-ws `complete` (server restarted the API, subscription
        // ended) ended the old flow silently, so the card froze. The loop must
        // surface it as an Error and open a new attempt after the first backoff.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf(
                { flowOf(s("S1")) },
                { liveForever("S2") },
            ),
        )
        val out = mutableListOf<DomainState<String>>()
        val job = launch { resubscribing(script::attempt).collect { out += it } }
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()

        assertEquals(listOf(s("S1"), DomainState.Error("Live stream ended"), s("S2")), out)
        assertEquals(listOf(0L, 1_000L), script.calls)
        job.cancel()
    }

    @Test
    fun completedStreamViaCombinatorGoesLiveAgainWithoutPoll() = runTest {
        // WHY: same event as above, seen from the card: S1, a completion, S2 --
        // the quick resubscribe lands in the grace window, so no poll at all.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf(
                { liveThen() },
                { liveForever("S2") },
            ),
        )
        var pollStarts = 0
        val out = mutableListOf<DomainState<String>>()
        val job = launch {
            subscriptionOrPoll(
                resubscribing(script::attempt),
                pollCounting { pollStarts++ },
                UnraidRepository.SUB_FALLBACK_GRACE_MS,
            ).collect { out += it }
        }
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()

        assertEquals(listOf("S1", "S2"), values(out))
        assertEquals(0, pollStarts)
        job.cancel()
    }

    @Test
    fun outageReachesPollAndErrorState() = runTest {
        // WHY: with the socket AND the HTTP path down, the card must show the
        // error state, never the last live value as if it were current. After
        // the grace window the poll's Error must be the last thing downstream.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf(
                { liveThen(DomainState.Error("socket closed")) },
                { flowOf(DomainState.Error("refused")) },
            ),
        )
        val out = mutableListOf<DomainState<String>>()
        val job = launch {
            subscriptionOrPoll(
                resubscribing(script::attempt),
                UnraidRepository.pollDomainForTest(UnraidRepository.POLL_METRICS_MS) {
                    DomainState.Error("server not reachable")
                },
                UnraidRepository.SUB_FALLBACK_GRACE_MS,
            ).collect { out += it }
        }
        // The first Error comes after the frame gap; the grace window runs from it.
        testScheduler.advanceTimeBy(frameGapMs + UnraidRepository.SUB_FALLBACK_GRACE_MS - 1)
        testScheduler.runCurrent()
        assertEquals("inside the grace the last live frame stands", s("S1"), out.last())

        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertEquals(DomainState.Error("server not reachable"), out.last())
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertTrue(out.last() is DomainState.Error)
        job.cancel()
    }

    @Test
    fun equalFramesDoNotTripSilenceWatchdog() = runTest {
        // WHY: silence is measured by frame ARRIVAL. A server that sends the same
        // value again and again (idle machine, constant temperature) is healthy;
        // the combinator's StateFlow drops equal values, so a watchdog built on
        // downstream changes would reconnect every 15 s for nothing.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf({
                flow { while (true) { emit(s("SAME")); delay(5_000) } }
            }),
        )
        var pollStarts = 0
        val raw = mutableListOf<DomainState<String>>()
        val out = mutableListOf<DomainState<String>>()
        val rawJob = launch { resubscribing(script::attempt).collect { raw += it } }
        val script2 = Script(testScheduler, listOf({ flow { while (true) { emit(s("SAME")); delay(5_000) } } }))
        val job = launch {
            subscriptionOrPoll(
                resubscribing(script2::attempt),
                pollCounting { pollStarts++ },
                UnraidRepository.SUB_FALLBACK_GRACE_MS,
            ).collect { out += it }
        }
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()

        assertEquals(1, script.calls.size)
        assertEquals(1, script2.calls.size)
        assertTrue(raw.isNotEmpty() && raw.all { it == s("SAME") })
        assertEquals(0, pollStarts)
        assertEquals(listOf(s("SAME")), out)
        rawJob.cancel()
        job.cancel()
    }

    @Test
    fun dataLessErrorFrameDegradesWithoutResubscribe() = runTest {
        // WHY: a frame with GraphQL errors only (no data, no exception) does not
        // mean the operation is dead; the next data frame recovers. Tearing the
        // subscription down for it would churn the socket for nothing, but the
        // card must still see the Error.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf({
                flow {
                    emit(s("S1"))
                    emit(DomainState.Error("field error"))
                    delay(1_000)
                    while (true) { emit(s("S2")); delay(5_000) }
                }
            }),
        )
        val out = mutableListOf<DomainState<String>>()
        val job = launch { resubscribing(script::attempt).collect { out += it } }
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()

        assertEquals(listOf(s("S1"), DomainState.Error("field error"), s("S2")), out.take(3))
        assertTrue("only live S2 frames after recovery, no Error", out.drop(3).all { it == s("S2") })
        assertEquals(1, script.calls.size)
        job.cancel()
    }

    @Test
    fun errorFramesCountAsLifeForTheSilenceWatchdog() = runTest {
        // WHY: "any frame resets the timer" -- an attempt that keeps answering with
        // data-less error frames is alive, not silent; replacing it would hide the
        // server's real message behind a generic "No live data".
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf({ flow { while (true) { emit(DomainState.Error("busy")); delay(10_000) } } }),
        )
        val job = launch { resubscribing(script::attempt).collect { } }
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(1, script.calls.size)
        job.cancel()
    }

    @Test
    fun attemptThatThrowsBecomesErrorAndIsRetried() = runTest {
        // WHY: an unexpected exception in the Apollo flow must not end the stream
        // (the old code emitted one Error and stopped for good).
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val script = Script(
            testScheduler,
            listOf(
                { flow { throw IllegalStateException("boom") } },
                { liveForever("S2") },
            ),
        )
        val out = mutableListOf<DomainState<String>>()
        val job = launch { resubscribing(script::attempt).collect { out += it } }
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(listOf(DomainState.Error("boom"), s("S2")), out)
        job.cancel()
    }

    @Test
    fun backoffResetsAfterHealthyFrameAndIsCapped() = runTest {
        // WHY: the schedule is the contract with the server: 1 s doubling to a
        // 30 s cap (a stream that fails for good must not hammer the API), and a
        // healthy frame starts over at 1 s (a stream that worked must reconnect
        // fast next time). Fails without the cap, or without the reset.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val fail: () -> Flow<DomainState<String>> = { flowOf(DomainState.Error("down")) }
        val script = Script(
            testScheduler,
            listOf(fail, fail, { flowOf(s("S1")) }, fail),
        )
        val job = launch { resubscribing(script::attempt).collect { } }
        testScheduler.advanceTimeBy(200_000)
        testScheduler.runCurrent()

        val gaps = script.gaps
        // fail -> 1 s, fail -> 2 s, Content then end -> 1 s (reset), then failing:
        // 2, 4, 8, 16, then capped at 30.
        assertEquals(
            listOf(1_000L, 2_000L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            gaps.take(9),
        )
        assertTrue("no wait may exceed the cap", gaps.all { it <= UnraidRepository.SUB_RETRY_MAX_MS })
        job.cancel()
    }

    @Test
    fun cancellationStopsRetryingAndNewCollectionStartsFresh() = runTest {
        // WHY: the lifecycle gate (tab left, app in background) cancels the
        // collector; a retry loop that outlived it would keep opening sockets in
        // the background. A new collection must start at once and from the
        // first backoff step, not from where the old one stopped.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val fail: () -> Flow<DomainState<String>> = { flowOf(DomainState.Error("down")) }
        val script = Script(testScheduler, listOf(fail))
        val job = launch { resubscribing(script::attempt).collect { } }
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(1, script.calls.size)

        job.cancel()
        testScheduler.advanceTimeBy(5 * 60_000)
        testScheduler.runCurrent()
        assertEquals("no attempt after the cancel", 1, script.calls.size)

        val restartedAt = testScheduler.currentTime
        val job2 = launch { resubscribing(script::attempt).collect { } }
        testScheduler.runCurrent()
        assertEquals(2, script.calls.size)
        assertEquals("a new collection starts without delay", restartedAt, script.calls[1])
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals("and with the first backoff step", restartedAt + 1_000, script.calls[2])
        job2.cancel()
    }

    @Test
    fun serverSwitchWithSilentNewSubscriptionReachesItsPollAfterTheGrace() = runTest {
        // WHY: after a server switch the combinator must start from "no frame yet"
        // for the NEW server. Created once outside the switch, it kept the old
        // server's last frame as its state and showed it until the silent new
        // subscription timed out (15 s) plus the grace (6 s). Per server, the
        // new server's poll takes over after the grace alone.
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val active = MutableStateFlow("A")
        var aCancelled = false
        val out = mutableListOf<DomainState<String>>()
        val job = launch {
            perServer(active) { server ->
                subscriptionOrPoll(
                    resubscribing({
                        if (server == "A") {
                            flow<DomainState<String>> { emit(DomainState.Content("A-live", "A")); awaitCancellation() }
                                .onCompletion { aCancelled = true }
                        } else {
                            flow { awaitCancellation() }
                        }
                    }),
                    flow { emit(DomainState.Content("$server-poll", server)); awaitCancellation() },
                    UnraidRepository.SUB_FALLBACK_GRACE_MS,
                )
            }.collect { out += it }
        }
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertEquals(DomainState.Content("A-live", "A"), out.last())

        active.value = "B"
        testScheduler.runCurrent()
        assertTrue("old server's subscription is cancelled on the switch", aCancelled)
        testScheduler.advanceTimeBy(UnraidRepository.SUB_FALLBACK_GRACE_MS - 1)
        testScheduler.runCurrent()
        assertEquals(DomainState.Content("A-live", "A"), out.last())

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(DomainState.Content("B-poll", "B"), out.last())
        job.cancel()
    }
}
