package io.github.nofuturekid.nova.data.model

/**
 * What NOVA knows about the server it talks to and about what its API key may
 * do (ADR-0047). Pure Kotlin on purpose: no Apollo and no Android types, so the
 * rules below are unit-tested on the JVM.
 *
 * One rule runs through all of it: UNKNOWN IS NOT DENIED. A failed detection, a
 * key whose `me` is refused and a role NOVA has never heard of all leave the
 * answer at [Access.Unknown]. Only a successful detection can say [Access.Denied],
 * so a transient error can never take a button away from the user.
 */

/** The server address and API key that capabilities were detected for. Equality only; never logged. */
data class CapabilityTarget(val serverId: String, val baseUrl: String, private val apiKey: String) {
    override fun toString() = "CapabilityTarget($serverId, $baseUrl, key=***)"
}

/** An Unraid API version, compared by number only (a build suffix is not part of the order). */
data class ApiVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ApiVersion> {
    override fun compareTo(other: ApiVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val LEADING_NUMBERS = Regex("""^\s*(\d+)\.(\d+)\.(\d+)""")

        /** Leading x.y.z of "4.37.11+8e587fe2", "4.36.1+f3205d97" or "4.37.11-2"; null for anything else. */
        fun parse(raw: String?): ApiVersion? {
            val (a, b, c) = raw?.let { LEADING_NUMBERS.find(it) }?.destructured ?: return null
            return ApiVersion(
                a.toIntOrNull() ?: return null,
                b.toIntOrNull() ?: return null,
                c.toIntOrNull() ?: return null,
            )
        }
    }
}

/** An API capability gated by the server's API version, with the first version that has it. */
enum class ApiFeature(val since: ApiVersion) {
    /** Every screen (#247). */
    Base(ApiVersion(4, 30, 0)),

    /** Documented here; the existing network-speed probe stays. */
    NetworkSpeed(ApiVersion(4, 35, 0)),

    /** `docker.restart` in one step (#317). */
    DockerRestart(ApiVersion(4, 36, 0)),
}

/**
 * A control NOVA offers and the permission the server checks for it. The single source for the
 * hide-controls work (#315) and the documentation of key permissions (#316).
 *
 * [operations] are the names of the GraphQL operations in `mutations.graphql` behind the control.
 */
enum class KeyAction(val resource: String, val action: String, val operations: Set<String>) {
    ControlArray(
        "ARRAY", "UPDATE_ANY",
        setOf("StartArray", "StopArray", "StartParityCheck", "PauseParityCheck", "ResumeParityCheck", "CancelParityCheck"),
    ),
    ControlDocker(
        "DOCKER", "UPDATE_ANY",
        setOf(
            "StartContainer", "StopContainer", "RestartContainer", "PauseContainer", "UnpauseContainer",
            "UpdateContainer", "UpdateAllContainers",
        ),
    ),
    ControlVms(
        "VMS", "UPDATE_ANY",
        setOf("StartVm", "StopVm", "PauseVm", "ResumeVm", "ForceStopVm", "RebootVm", "ResetVm"),
    ),
    UpdateNotifications(
        "NOTIFICATIONS", "UPDATE_ANY",
        setOf("ArchiveNotification", "UnreadNotification", "ArchiveAllNotifications", "RecalculateNotificationOverview"),
    ),
    DeleteNotifications(
        "NOTIFICATIONS", "DELETE_ANY",
        setOf("DeleteNotification", "DeleteArchivedNotifications"),
    ),
    ;

    /** The permission in the server's own spelling, e.g. `DOCKER:UPDATE_ANY`. */
    val permission: String get() = "$resource:$action"

    companion object {
        fun forOperation(name: String): KeyAction? = entries.firstOrNull { name in it.operations }
    }
}

/** What the key may do. [Unknown] is not [Denied]: the UI hides a control only for [Denied]. */
enum class Access { Allowed, Denied, Unknown }

/** Whether the server's API has a feature. [Unknown] is not [No]. */
enum class Support { Yes, No, Unknown }

sealed interface KeyAccess {
    data object Unknown : KeyAccess

    /** [roles] and [grants] as the server's raw enum names, e.g. "VIEWER" and "DOCKER:UPDATE_ANY". */
    data class Known(val roles: Set<String>, val grants: Set<String>) : KeyAccess
}

enum class Detection {
    /** Nothing detected yet (no server, or no key). */
    Idle,

    /** Detection for [ServerCapabilities.target] is running and no earlier result exists for it. */
    Detecting,

    /** The server answered; the version or the access (or both) is known. */
    Done,

    /** The server said `UNAUTHENTICATED`. Only ever set from that answer. */
    KeyRejected,

    /** No usable answer (unreachable, server error). Version and access stay unknown. */
    Failed,
}

data class ServerCapabilities(
    val target: CapabilityTarget? = null,
    val detection: Detection = Detection.Idle,
    val unraidVersion: String? = null,
    /** The raw API version, for display ("4.37.11+8e587fe2"). */
    val apiVersionText: String? = null,
    val apiVersion: ApiVersion? = null,
    /** The version query failed validation, so the API is older than 4.15.0 (the field does not exist there). */
    val versionFieldMissing: Boolean = false,
    val access: KeyAccess = KeyAccess.Unknown,
) {
    /**
     * Whether the key may use [a]: [Access.Allowed] when the key holds the permission explicitly or one of
     * its roles grants it; [Access.Unknown] when nothing is known or a role is unknown to NOVA and no known
     * role grants it (a future role must never hide a control); otherwise [Access.Denied].
     */
    fun access(a: KeyAction): Access {
        val known = access as? KeyAccess.Known ?: return Access.Unknown
        if (a.permission in known.grants) return Access.Allowed
        val verdicts = known.roles.map { roleGrants(it, a.resource, a.action) }
        return when {
            verdicts.any { it == true } -> Access.Allowed
            verdicts.any { it == null } -> Access.Unknown
            else -> Access.Denied
        }
    }

    fun supports(f: ApiFeature): Support = when {
        versionFieldMissing -> Support.No
        apiVersion == null -> Support.Unknown
        apiVersion >= f.since -> Support.Yes
        else -> Support.No
    }

    /** True only when the server proved it: the version is below the floor, or the version field does not exist. */
    val apiTooOld: Boolean get() = supports(ApiFeature.Base) == Support.No

    companion object {
        val UNKNOWN = ServerCapabilities()
    }
}

/**
 * Static mirror of the Unraid API's role policy (`api/src/unraid-api/auth/casbin/policy.ts`; identical from
 * v4.30.0 to v4.37.11 and main). `true` = the role grants it, `false` = it does not, `null` = a role NOVA
 * does not know. The server compares the action exactly: UPDATE_ANY does not imply READ_ANY and `*_OWN`
 * never satisfies an `*_ANY` check.
 */
internal fun roleGrants(role: String, resource: String, action: String): Boolean? = when (role) {
    "ADMIN" -> true
    "VIEWER" -> action == "READ_ANY" && resource != "API_KEY"
    // CONNECT inherits VIEWER and may update its own remote-access setting.
    "CONNECT" -> (action == "READ_ANY" && resource != "API_KEY") ||
        (resource == "CONNECT__REMOTE_ACCESS" && action == "UPDATE_ANY")
    "GUEST" -> resource == "ME" && action == "READ_ANY"
    else -> null
}
