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
    private fun stat(cpu: Double) =
        ContainerLiveStats(cpu, cpu, "${cpu}GiB", netIO = "", blockIO = "")

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
