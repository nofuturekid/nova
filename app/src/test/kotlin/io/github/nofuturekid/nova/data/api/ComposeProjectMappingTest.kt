package io.github.nofuturekid.nova.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Why: the Compose layout is only as good as the label read behind it. Anything
 *  that is not a plain project name must become "no project", never a wrong one. */
class ComposeProjectMappingTest {
    @Test fun reads_the_compose_project_label() {
        val labels = mapOf("com.docker.compose.project" to "media", "net.unraid.docker.managed" to "dockerman")
        assertEquals("media", parseComposeProject(labels))
    }

    @Test fun trims_the_value() {
        assertEquals("media", parseComposeProject(mapOf(COMPOSE_PROJECT_LABEL to " media ")))
    }

    /** Why: template-based Unraid containers carry labels, just not this one. */
    @Test fun containers_without_the_label_have_no_project() {
        assertNull(parseComposeProject(mapOf("net.unraid.docker.managed" to "dockerman")))
        assertNull(parseComposeProject(emptyMap<String, Any?>()))
    }

    /** Why: the schema types labels as nullable JSON (current servers send {} for none); null and other shapes must not crash. */
    @Test fun missing_blank_or_odd_labels_have_no_project() {
        assertNull(parseComposeProject(null))
        assertNull(parseComposeProject(mapOf(COMPOSE_PROJECT_LABEL to "")))
        assertNull(parseComposeProject(mapOf(COMPOSE_PROJECT_LABEL to "   ")))
        assertNull(parseComposeProject(mapOf(COMPOSE_PROJECT_LABEL to 42)))
        assertNull(parseComposeProject(listOf("com.docker.compose.project")))
        assertNull(parseComposeProject("com.docker.compose.project=media"))
    }
}
