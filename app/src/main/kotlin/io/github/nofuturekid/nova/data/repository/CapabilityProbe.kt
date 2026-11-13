package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Error
import io.github.nofuturekid.nova.data.model.ApiVersion
import io.github.nofuturekid.nova.data.model.CapabilityTarget
import io.github.nofuturekid.nova.data.model.Detection
import io.github.nofuturekid.nova.data.model.KeyAccess
import io.github.nofuturekid.nova.data.model.ServerCapabilities
import io.github.nofuturekid.nova.graphql.GetApiVersionQuery
import io.github.nofuturekid.nova.graphql.GetKeyAccessQuery
import kotlinx.coroutines.CancellationException

/**
 * Detection of the server's API version and the API key's access (ADR-0047): the two
 * operations `GetApiVersion` and `GetKeyAccess`, and the classification of what the server
 * answers. The mapping from answers to capabilities lives here, not in `GraphQlMapper.kt`.
 *
 * The rule for every branch: only a SUCCESSFUL answer can take something away. A refused or
 * failed `me` leaves the access unknown (controls stay visible, as before this feature);
 * only `UNAUTHENTICATED` from the server marks the key as rejected, never an inference.
 */

/** The result of one detection, before it is tied to a target (see [toCapabilities]). */
data class ProbeOutcome(
    val detection: Detection,
    val unraidVersion: String? = null,
    val apiVersionText: String? = null,
    val apiVersion: ApiVersion? = null,
    /** The version query failed validation: the API is older than 4.15.0. */
    val versionFieldMissing: Boolean = false,
    val access: KeyAccess = KeyAccess.Unknown,
) {
    fun toCapabilities(target: CapabilityTarget?) = ServerCapabilities(
        target = target,
        detection = detection,
        unraidVersion = unraidVersion,
        apiVersionText = apiVersionText,
        apiVersion = apiVersion,
        versionFieldMissing = versionFieldMissing,
        access = access,
    )
}

/** What the `errors` of one GraphQL response say, by `extensions.code`. First match wins in this order. */
internal enum class ErrorClass { KeyRejected, ValidationFailed, Forbidden, Other }

internal fun classifyErrors(errors: List<Error>): ErrorClass = when {
    errors.any { it.code() == "UNAUTHENTICATED" } -> ErrorClass.KeyRejected
    // Apollo parses Unraid's HTTP 400 `application/graphql-response+json` body into `errors`.
    errors.any { it.code() == "GRAPHQL_VALIDATION_FAILED" || it.message.startsWith("Cannot query field") } ->
        ErrorClass.ValidationFailed
    errors.any { it.code() == "FORBIDDEN" } -> ErrorClass.Forbidden
    else -> ErrorClass.Other
}

private fun Error.code(): Any? = extensions?.get("code")

private sealed interface VersionStep {
    data class Got(val unraid: String?, val apiText: String?) : VersionStep
    data object Missing : VersionStep
    data object Unknown : VersionStep
    data object Rejected : VersionStep
    data object Failed : VersionStep
}

private sealed interface AccessStep {
    data class Got(val access: KeyAccess.Known) : AccessStep
    data object Unknown : AccessStep
    data object Rejected : AccessStep
    data object TransportFailed : AccessStep
}

/**
 * Sends `GetApiVersion`, then `GetKeyAccess`, and classifies each answer. Never throws for a
 * server or transport problem (an [com.apollographql.apollo.exception.ApolloException] counts as
 * a transport failure); only cancellation passes through.
 */
internal suspend fun probeCapabilities(client: ApolloClient): ProbeOutcome {
    val version = probeVersion(client)
    when (version) {
        VersionStep.Rejected -> return ProbeOutcome(Detection.KeyRejected)
        VersionStep.Failed -> return ProbeOutcome(Detection.Failed)
        else -> Unit
    }
    val versionKnown = version is VersionStep.Got || version == VersionStep.Missing
    val base = when (version) {
        is VersionStep.Got -> ProbeOutcome(
            Detection.Done,
            unraidVersion = version.unraid,
            apiVersionText = version.apiText,
            apiVersion = ApiVersion.parse(version.apiText),
        )
        VersionStep.Missing -> ProbeOutcome(Detection.Done, versionFieldMissing = true)
        else -> ProbeOutcome(Detection.Done)
    }
    return when (val access = probeAccess(client)) {
        is AccessStep.Got -> base.copy(access = access.access)
        AccessStep.Unknown -> base
        AccessStep.Rejected -> ProbeOutcome(Detection.KeyRejected)
        AccessStep.TransportFailed -> if (versionKnown) base else ProbeOutcome(Detection.Failed)
    }
}

private suspend fun probeVersion(client: ApolloClient): VersionStep = try {
    val resp = client.query(GetApiVersionQuery()).execute()
    val core = resp.data?.info?.versions?.core
    val errors = resp.errors.orEmpty()
    when {
        core != null -> VersionStep.Got(core.unraid, core.api)
        errors.isNotEmpty() -> when (classifyErrors(errors)) {
            ErrorClass.KeyRejected -> VersionStep.Rejected
            ErrorClass.ValidationFailed -> VersionStep.Missing
            ErrorClass.Forbidden -> VersionStep.Unknown
            ErrorClass.Other -> VersionStep.Failed
        }
        // Transport failure, or a body without data and without errors.
        else -> VersionStep.Failed
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    VersionStep.Failed
}

private suspend fun probeAccess(client: ApolloClient): AccessStep = try {
    val resp = client.query(GetKeyAccessQuery()).execute()
    val me = resp.data?.me
    val errors = resp.errors.orEmpty()
    when {
        me != null -> AccessStep.Got(
            KeyAccess.Known(
                roles = me.roles.map { it.rawValue }.toSet(),
                grants = me.permissions.orEmpty().flatMap { p ->
                    p.actions.map { "${p.resource.rawValue}:${it.rawValue}" }
                }.toSet(),
            ),
        )
        errors.isNotEmpty() -> when (classifyErrors(errors)) {
            ErrorClass.KeyRejected -> AccessStep.Rejected
            // FORBIDDEN (a key without ME:READ_ANY), validation and any other error: fail open.
            else -> AccessStep.Unknown
        }
        else -> AccessStep.TransportFailed
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AccessStep.TransportFailed
}
