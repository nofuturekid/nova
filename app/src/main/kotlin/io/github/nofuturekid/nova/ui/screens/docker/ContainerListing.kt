package io.github.nofuturekid.nova.ui.screens.docker

import io.github.nofuturekid.nova.data.model.Container

/** Title of the group that holds containers without a Compose project. */
const val NO_COMPOSE_PROJECT = "No Compose project"

/** One group of the Compose layout. [project] is null for the containers without one. */
data class ComposeGroup(val project: String?, val containers: List<Container>) {
    val title: String get() = project ?: NO_COMPOSE_PROJECT
}

/**
 * The containers whose name or image contains [query].
 *
 * Case does not matter and spaces around the query are ignored, so
 * "  Plex " finds `plex`. A blank query is "no filter": every container
 * comes back, in the order it came in.
 */
fun filterContainers(containers: List<Container>, query: String): List<Container> {
    val q = query.trim()
    if (q.isEmpty()) return containers
    return containers.filter {
        it.name.contains(q, ignoreCase = true) || it.image.contains(q, ignoreCase = true)
    }
}

/**
 * Group [containers] by Compose project.
 *
 * Projects are sorted by name (case-insensitive); the group of containers
 * without a project always comes last. Inside a group the containers keep
 * the order they came in, so the layout does not reshuffle on every poll.
 * Every container lands in exactly one group, none is dropped.
 */
fun groupByComposeProject(containers: List<Container>): List<ComposeGroup> =
    containers
        .groupBy { c -> c.composeProject?.trim()?.takeIf { it.isNotEmpty() } }
        .map { (project, members) -> ComposeGroup(project, members) }
        .sortedWith(
            compareBy<ComposeGroup> { it.project == null }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.project.orEmpty() }
                .thenBy { it.project.orEmpty() },
        )
