package io.github.nofuturekid.nova.data.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0047. The model decides which controls a key may be offered, so every test here
 * states the user-facing reason, not just the table lookup.
 */
class ServerCapabilitiesTest {
    private fun withAccess(roles: Set<String>, grants: Set<String> = emptySet()) =
        ServerCapabilities(detection = Detection.Done, access = KeyAccess.Known(roles, grants))

    private fun ServerCapabilities.everyAction() = KeyAction.entries.associateWith { access(it) }

    // WHY: a read-only key must not be offered buttons the server will refuse (#315).
    @Test fun viewerKeyMayReadButNotControl() {
        assertTrue(withAccess(setOf("VIEWER")).everyAction().values.all { it == Access.Denied })
    }

    @Test fun adminKeyMayDoEverything() {
        assertTrue(withAccess(setOf("ADMIN")).everyAction().values.all { it == Access.Allowed })
    }

    // WHY: the least-privilege key (VIEWER plus a few explicit permissions) must get exactly its buttons.
    @Test fun explicitPermissionsAddToRole() {
        val caps = withAccess(setOf("VIEWER"), setOf("DOCKER:UPDATE_ANY"))
        assertEquals(Access.Allowed, caps.access(KeyAction.ControlDocker))
        assertEquals(Access.Denied, caps.access(KeyAction.ControlVms))
        assertEquals(Access.Denied, caps.access(KeyAction.DeleteNotifications))
    }

    // WHY: a key can hold permissions and no role at all; its grants alone decide.
    @Test fun keyWithoutRolesUsesItsPermissions() {
        val caps = withAccess(emptySet(), setOf("VMS:UPDATE_ANY"))
        assertEquals(Access.Allowed, caps.access(KeyAction.ControlVms))
        assertEquals(Access.Denied, caps.access(KeyAction.ControlDocker))
    }

    // WHY: the server matches UPDATE_ANY exactly; an *_OWN grant never satisfies the guard.
    @Test fun ownActionsDoNotCount() {
        assertEquals(Access.Denied, withAccess(emptySet(), setOf("DOCKER:UPDATE_OWN")).access(KeyAction.ControlDocker))
    }

    // WHY: UPDATE_ANY does not imply DELETE_ANY on the server, so notifications split in two.
    @Test fun updateNotificationsDoesNotAllowDelete() {
        val caps = withAccess(emptySet(), setOf("NOTIFICATIONS:UPDATE_ANY"))
        assertEquals(Access.Allowed, caps.access(KeyAction.UpdateNotifications))
        assertEquals(Access.Denied, caps.access(KeyAction.DeleteNotifications))
    }

    // WHY: a role a newer Unraid adds must never hide a control NOVA cannot judge.
    @Test fun unknownRoleFailsOpen() {
        val caps = withAccess(setOf("FUTURE_ROLE"))
        assertTrue(caps.everyAction().values.all { it == Access.Unknown })
    }

    // WHY: a known role that grants it still wins over an unknown role next to it.
    @Test fun knownGrantBeatsUnknownRole() {
        assertEquals(Access.Allowed, withAccess(setOf("FUTURE_ROLE", "ADMIN")).access(KeyAction.ControlArray))
    }

    // WHY: "the permission query failed" is not "the key may not" (#315: the app then behaves as today).
    @Test fun unknownAccessIsNeverDenied() {
        assertTrue(ServerCapabilities.UNKNOWN.everyAction().values.all { it == Access.Unknown })
        assertTrue(ServerCapabilities(detection = Detection.Failed).everyAction().values.all { it == Access.Unknown })
    }

    // WHY: this pins our copy of the server's role policy (policy.ts) so drift shows up as a failing test.
    @Test fun roleTableMirrorsUpstream() {
        assertEquals(false, roleGrants("VIEWER", "API_KEY", "READ_ANY"))
        assertEquals(true, roleGrants("VIEWER", "DOCKER", "READ_ANY"))
        assertEquals(true, roleGrants("VIEWER", "ME", "READ_ANY"))
        assertEquals(false, roleGrants("VIEWER", "DOCKER", "UPDATE_ANY"))
        assertEquals(true, roleGrants("ADMIN", "API_KEY", "DELETE_ANY"))
        assertEquals(true, roleGrants("CONNECT", "CONNECT__REMOTE_ACCESS", "UPDATE_ANY"))
        assertEquals(false, roleGrants("CONNECT", "DOCKER", "UPDATE_ANY"))
        assertEquals(true, roleGrants("GUEST", "ME", "READ_ANY"))
        assertEquals(false, roleGrants("GUEST", "DOCKER", "READ_ANY"))
        assertNull(roleGrants("FUTURE_ROLE", "DOCKER", "READ_ANY"))
    }

    // WHY: a new control must not bypass hiding and the documented permission table (#315, #316).
    @Test fun everyMutationHasAKeyAction() {
        val dir = listOf(File("src/main/graphql"), File("app/src/main/graphql")).first { it.isDirectory }
        val source = File(dir, "io/github/nofuturekid/nova/mutations.graphql").readLines()
            .filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        val names = Regex("""\bmutation\s+(\w+)""").findAll(source).map { it.groupValues[1] }.toList()
        assertTrue("no mutations found", names.size > 20)
        for (n in names) {
            val owners = KeyAction.entries.filter { n in it.operations }
            assertEquals("mutation $n must belong to exactly one KeyAction", 1, owners.size)
            assertEquals(owners.single(), KeyAction.forOperation(n))
        }
    }

    // WHY: one operation behind two permissions would make "hide" and "document" disagree.
    @Test fun noOperationBelongsToTwoActions() {
        val all = KeyAction.entries.flatMap { it.operations.toList() }
        assertEquals(all.toSet().size, all.size)
    }

    @Test fun permissionIsSpelledLikeTheServer() {
        assertEquals("DOCKER:UPDATE_ANY", KeyAction.ControlDocker.permission)
        assertEquals("NOTIFICATIONS:DELETE_ANY", KeyAction.DeleteNotifications.permission)
    }

    // WHY: the API string carries a build suffix; the order must ignore it.
    @Test fun parsesApiVersionWithBuildSuffix() {
        assertEquals(ApiVersion(4, 37, 11), ApiVersion.parse("4.37.11+8e587fe2"))
        assertEquals(ApiVersion(4, 36, 1), ApiVersion.parse("4.36.1+f3205d97"))
        assertEquals(ApiVersion(4, 37, 11), ApiVersion.parse("4.37.11-2"))
        assertNull(ApiVersion.parse(""))
        assertNull(ApiVersion.parse(null))
        assertNull(ApiVersion.parse("unknown"))
        assertNull(ApiVersion.parse("4.37"))
    }

    // WHY: 4.9.0 is older than 4.30.0 although "9" sorts after "30" as text.
    @Test fun versionsCompareByNumber() {
        assertTrue(ApiVersion(4, 9, 0) < ApiVersion(4, 30, 0))
        assertTrue(ApiVersion(4, 36, 0) > ApiVersion(4, 35, 9))
        assertTrue(ApiVersion(5, 0, 0) > ApiVersion(4, 99, 99))
    }

    private fun caps(version: String?, fieldMissing: Boolean = false) = ServerCapabilities(
        detection = Detection.Done, apiVersion = ApiVersion.parse(version), versionFieldMissing = fieldMissing,
    )

    // WHY: sending docker.restart to a 4.35 server fails validation; unknown must not claim support.
    @Test fun restartNeeds436() {
        assertEquals(Support.No, caps("4.35.1").supports(ApiFeature.DockerRestart))
        assertEquals(Support.Yes, caps("4.36.0").supports(ApiFeature.DockerRestart))
        assertEquals(Support.Yes, caps("4.37.11+8e587fe2").supports(ApiFeature.DockerRestart))
        assertEquals(Support.Unknown, caps(null).supports(ApiFeature.DockerRestart))
    }

    @Test fun floorsAreTheDocumentedOnes() {
        assertEquals(ApiVersion(4, 30, 0), ApiFeature.Base.since)
        assertEquals(ApiVersion(4, 35, 0), ApiFeature.NetworkSpeed.since)
        assertEquals(ApiVersion(4, 36, 0), ApiFeature.DockerRestart.since)
        assertEquals(Support.No, caps("4.29.2").supports(ApiFeature.Base))
        assertEquals(Support.Yes, caps("4.30.0").supports(ApiFeature.Base))
        assertEquals(Support.No, caps("4.34.0").supports(ApiFeature.NetworkSpeed))
        assertEquals(Support.Yes, caps("4.35.0").supports(ApiFeature.NetworkSpeed))
    }

    // WHY: "too old" is claimed only when the server proved it; unknown must not show an "API too old" hint.
    @Test fun apiTooOldNeedsProof() {
        assertTrue(caps("4.29.2").apiTooOld)
        assertTrue(caps(null, fieldMissing = true).apiTooOld)
        assertFalse(caps(null).apiTooOld)
        assertFalse(ServerCapabilities.UNKNOWN.apiTooOld)
        assertFalse(caps("4.30.0").apiTooOld)
    }

    // WHY: the key must never reach a log line through the target.
    @Test fun targetNeverPrintsTheKey() {
        val t = CapabilityTarget("s1", "http://tower", "secret-key-123")
        assertFalse(t.toString().contains("secret-key-123"))
        assertEquals(t, CapabilityTarget("s1", "http://tower", "secret-key-123"))
        assertTrue(t != CapabilityTarget("s1", "http://tower", "other-key"))
    }
}
