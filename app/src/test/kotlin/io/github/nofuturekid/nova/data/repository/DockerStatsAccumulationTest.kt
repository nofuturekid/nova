package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.api.toContainerLiveStat
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.graphql.DockerContainerStatsSubscription
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DockerStatsAccumulationTest {
    private fun stat(cpu: Double, mem: Double, usage: String) = ContainerLiveStats(
        cpuPercent = cpu,
        memPercent = mem,
        memUsage = usage,
        netIO = "",
        blockIO = "",
    )

    /** WHY: the subscription op selects netIO + blockIO (since beta2), but the
     *  mapper used to drop them — so the container detail sheet could never show
     *  network / disk I/O. This pins that BOTH carry through verbatim (they're
     *  preformatted "RX / TX" and "read / write" strings); a regression that
     *  re-drops either fails here. */
    @Test fun mapper_carries_netio_and_blockio_through() {
        val data = DockerContainerStatsSubscription.Data(
            dockerContainerStats = DockerContainerStatsSubscription.DockerContainerStats(
                id = "c1",
                cpuPercent = 2.6,
                memUsage = "236.1MiB / 2.00GiB",
                memPercent = 11.5,
                netIO = "2.17GiB / 12.7MiB",
                blockIO = "206.5MiB / 0B",
            ),
        )
        assertEquals(
            "c1" to ContainerLiveStats(
                cpuPercent = 2.6,
                memPercent = 11.5,
                memUsage = "236.1MiB / 2.00GiB",
                netIO = "2.17GiB / 12.7MiB",
                blockIO = "206.5MiB / 0B",
            ),
            data.toContainerLiveStat(),
        )
    }

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
