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
