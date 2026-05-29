package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.exception.ApolloException
import io.github.nofuturekid.nova.data.api.CertIssue
import io.github.nofuturekid.nova.data.api.certIssue

/**
 * Classification of an Apollo response. Apollo Kotlin 5's `execute()` does NOT
 * throw on network/TLS errors — it returns them in `ApolloResponse.exception`
 * (with `data == null`), and `hasErrors()` reflects only GraphQL-body errors.
 * This pure function centralises that handling so both [UnraidRepository.fetch]
 * and [UnraidRepository.testConnection] surface a self-signed cert correctly.
 */
sealed interface RespClass {
    data class Cert(val issue: CertIssue) : RespClass
    data class Failed(val message: String) : RespClass
    data object Empty : RespClass
    data object Ok : RespClass
}

/**
 * @param exception   ApolloResponse.exception (null when no transport error)
 * @param hasErrors   ApolloResponse.hasErrors() (GraphQL-body errors)
 * @param errorMessage joined GraphQL error messages, if any
 * @param dataIsNull  ApolloResponse.data == null
 */
fun classifyResponse(
    exception: ApolloException?,
    hasErrors: Boolean,
    errorMessage: String?,
    dataIsNull: Boolean,
): RespClass = when {
    exception != null -> {
        val issue = exception.certIssue()
        if (issue != null) RespClass.Cert(issue) else RespClass.Failed(exception.message ?: "Network error")
    }
    hasErrors -> RespClass.Failed(errorMessage ?: "Unknown GraphQL error")
    dataIsNull -> RespClass.Empty
    else -> RespClass.Ok
}

/** Structured result of a connection test (consumed by the Add/Edit sheet). */
sealed interface TestOutcome {
    data object Ok : TestOutcome
    data class Failed(val message: String) : TestOutcome
    data class CertUntrusted(val sha256: String) : TestOutcome
    data class CertChanged(val pinned: String, val presented: String) : TestOutcome
}
