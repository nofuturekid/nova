package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.graphql.DockerContainerStatsSubscription
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #224 — the Unraid API passes `docker stats` terminal output through, so the
 * first container of each frame arrives as `<serverid>:\u001B[J\u001B[H<containerid>`.
 * The stats map is joined to the polled containers by exact id; the polluted id
 * never matched, so that container never got live stats.
 */
class ContainerStatsIdTest {
    private val clean = "966da151bf3126fab6642f0a392b53cd8f87485d4346c491aa009d53ead12aa5:" +
        "cb71d505cb9bc2b629b4e3140f718764ec1eb7b56be448236188a5abe7ddbd4d"

    @Test fun pollutedId_normalisesToThePolledContainerId() {
        val polluted = clean.replace(":", ":\u001B[J\u001B[H")
        assertEquals(clean, polluted.stripTerminalControl())
    }

    @Test fun cleanId_isUnchanged() = assertEquals(clean, clean.stripTerminalControl())

    @Test fun otherControlCharacters_andSgrSequences_areStripped() {
        assertEquals("a:b", "a:\u001B[0m\u001B[2Jb\r\n\u0000".stripTerminalControl())
    }

    // WHY: the mapper is what feeds the join map; the fix must apply there.
    @Test fun mapper_emitsTheCleanId() {
        val data = DockerContainerStatsSubscription.Data(
            DockerContainerStatsSubscription.DockerContainerStats(
                id = clean.replace(":", ":\u001B[J\u001B[H"),
                cpuPercent = 1.0, memUsage = "1MiB / 2MiB", memPercent = 50.0, netIO = "0B / 0B", blockIO = "0B / 0B",
            ),
        )
        assertEquals(clean, data.toContainerLiveStat().first)
    }
}
