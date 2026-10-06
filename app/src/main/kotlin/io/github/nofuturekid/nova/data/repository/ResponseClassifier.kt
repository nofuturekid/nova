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
        if (issue != null) RespClass.Cert(issue) else RespClass.Failed(exception.failureMessage("Network error"))
    }
    hasErrors -> RespClass.Failed(errorMessage ?: "Unknown GraphQL error")
    dataIsNull -> RespClass.Empty
    else -> RespClass.Ok
}

/**
 * The text to show for a failed request: the exception's own message plus its
 * innermost cause. Apollo reports any parsing failure as "Error while reading
 * JSON response" and keeps the actual reason (a body that is not JSON, a null
 * in a non-null field, ...) only in the cause; without it such reports cannot
 * be told apart (#217).
 */
fun Throwable.failureMessage(fallback: String): String {
    val own = message ?: fallback
    var root: Throwable = this
    while (true) {
        val next = root.cause ?: break
        if (next === root) break
        root = next
    }
    if (root === this) return own
    val name = root::class.simpleName ?: "Exception"
    val rootText = root.message?.take(MAX_CAUSE_CHARS)?.let { "$name: $it" } ?: name
    return if (own.contains(rootText)) own else "$own ($rootText)"
}

private const val MAX_CAUSE_CHARS = 200

/** Structured result of a connection test (consumed by the Add/Edit sheet). */
sealed interface TestOutcome {
    data object Ok : TestOutcome
    data class Failed(val message: String) : TestOutcome
    data class CertUntrusted(val sha256: String) : TestOutcome
    data class CertChanged(val pinned: String, val presented: String) : TestOutcome
}
