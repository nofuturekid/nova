package io.github.nofuturekid.nova.ui.screens.docker

import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Why: with dozens of containers the user finds one by typing a few letters, and a
 *  Compose stack is one thing to manage, not N unrelated rows. A container that
 *  vanishes from the list, or a stack that jumps around, is worse than no feature. */
class ContainerListingTest {
    private fun c(id: String, image: String = "img", project: String? = null) = Container(
        id = id, name = id, image = image, status = ContainerStatus.Running,
        autoStart = false, iconColorHex = null, iconUrl = null, cpu = 0.0, memMb = 0,
        ports = emptyList(), volumes = emptyList(),
        updateStatus = ContainerUpdateStatus.Unknown, webUiUrl = null, networkIp = null,
        composeProject = project,
    )

    private val all = listOf(
        c("plex", "lscr.io/linuxserver/plex:latest"),
        c("Sonarr", "lscr.io/linuxserver/sonarr:latest"),
        c("db", "postgres:16"),
    )

    /** Why: users type what they remember. Sometimes that is the name, sometimes
     *  only the image ("postgres" for a container called `db`). */
    @Test fun search_matches_name_and_image() {
        assertEquals(listOf("plex"), filterContainers(all, "plex").map { it.id })
        assertEquals(listOf("db"), filterContainers(all, "postgres").map { it.id })
        assertEquals(listOf("plex", "Sonarr"), filterContainers(all, "linuxserver").map { it.id })
    }

    /** Why: a phone keyboard capitalises the first letter and adds a trailing space
     *  after autocomplete; neither may make a container disappear. */
    @Test fun search_ignores_case_and_surrounding_spaces() {
        assertEquals(listOf("Sonarr"), filterContainers(all, "  sonARR ").map { it.id })
        assertEquals(listOf("plex"), filterContainers(all, "PLEX").map { it.id })
    }

    /** Why: clearing the field must give back the full list, in the order it was
     *  before; a blank query is not a search for "nothing". */
    @Test fun empty_or_blank_query_returns_everything_in_order() {
        assertEquals(all, filterContainers(all, ""))
        assertEquals(all, filterContainers(all, "   "))
    }

    /** Why: the empty-result state in the UI relies on an empty list, not on a crash
     *  or on the old list coming back. */
    @Test fun a_query_without_a_match_returns_nothing() {
        assertEquals(emptyList<Container>(), filterContainers(all, "zzz"))
        assertEquals(emptyList<Container>(), filterContainers(emptyList(), "plex"))
    }

    /** Why: the stack list must read the same on every poll, and the odd ones out
     *  (no Compose) belong at the bottom, not between stacks. */
    @Test fun groups_are_sorted_by_project_with_unlabeled_last() {
        val list = listOf(
            c("loose-1"),
            c("z1", project = "zeta"),
            c("a1", project = "Alpha"),
            c("b1", project = "beta"),
        )
        val groups = groupByComposeProject(list)
        assertEquals(listOf("Alpha", "beta", "zeta", NO_COMPOSE_PROJECT), groups.map { it.title })
        assertNull(groups.last().project)
    }

    /** Why: the order of the server's list is what every other layout shows; the
     *  grouping must not reshuffle containers inside a stack. */
    @Test fun containers_keep_their_order_inside_a_group() {
        val list = listOf(
            c("web", project = "shop"), c("cache", project = "other"),
            c("db", project = "shop"), c("worker", project = "shop"),
        )
        val shop = groupByComposeProject(list).first { it.project == "shop" }
        assertEquals(listOf("web", "db", "worker"), shop.containers.map { it.id })
    }

    /** Why (hard constraint): grouping must never lose a container. Most Unraid
     *  containers come from templates, not Compose, so they are the majority. */
    @Test fun unlabeled_and_blank_project_containers_are_not_lost() {
        val list = listOf(c("a"), c("b", project = ""), c("c", project = "  "), c("d", project = "stack"))
        val groups = groupByComposeProject(list)
        assertEquals(list.map { it.id }.sorted(), groups.flatMap { it.containers }.map { it.id }.sorted())
        assertEquals(listOf("a", "b", "c"), groups.last().containers.map { it.id })
        assertEquals(NO_COMPOSE_PROJECT, groups.last().title)
    }

    /** Why: a project that is literally named like the fallback group must not
     *  swallow the containers that have no project. */
    @Test fun a_project_named_like_the_fallback_stays_a_separate_group() {
        val groups = groupByComposeProject(listOf(c("a", project = NO_COMPOSE_PROJECT), c("b")))
        assertEquals(2, groups.size)
        assertEquals(listOf("a"), groups.first().containers.map { it.id })
        assertNull(groups.last().project)
    }

    @Test fun no_containers_means_no_groups() {
        assertEquals(emptyList<ComposeGroup>(), groupByComposeProject(emptyList()))
    }

    /** Why: search and grouping are used together; a stack whose members are all
     *  filtered out must not show as an empty header. */
    @Test fun grouping_a_filtered_list_only_has_stacks_with_matches() {
        val list = listOf(c("web", project = "shop"), c("db", project = "shop"), c("grafana", project = "metrics"))
        val groups = groupByComposeProject(filterContainers(list, "graf"))
        assertEquals(listOf("metrics"), groups.map { it.title })
    }
}
