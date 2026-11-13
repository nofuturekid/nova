package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import io.github.nofuturekid.nova.data.api.CertIssue
import io.github.nofuturekid.nova.data.api.certIssue
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

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
 * Why a domain query failed, by the response it got. The first matching rule wins
 * (the bodies behind them were recorded from Unraid 7.4.0, API 4.37.11):
 *  1. a transport exception: [ErrorReason.Unreachable];
 *  2. `UNAUTHENTICATED`: [ErrorReason.KeyRejected];
 *  3. `GRAPHQL_VALIDATION_FAILED` or "Cannot query field": [ErrorReason.ApiTooOld];
 *  4. `FORBIDDEN`: [ErrorReason.Forbidden];
 *  5. "Docker socket unavailable" or "VMs are not available": [ErrorReason.ServiceOff];
 *  6. anything else: [ErrorReason.ServerError].
 * Rules 2 to 4 are [classifyErrors], shared with the capability probe so the two
 * cannot disagree about what an `errors` array means.
 */
fun classifyErrorReason(exception: ApolloException?, errors: List<Error>?): ErrorReason {
    if (exception != null) return ErrorReason.Unreachable
    val list = errors.orEmpty()
    return when (classifyErrors(list)) {
        ErrorClass.KeyRejected -> ErrorReason.KeyRejected
        ErrorClass.ValidationFailed -> ErrorReason.ApiTooOld
        ErrorClass.Forbidden -> ErrorReason.Forbidden
        ErrorClass.Other ->
            if (list.any { e -> SERVICE_OFF_MARKERS.any { e.message.contains(it) } }) ErrorReason.ServiceOff
            else ErrorReason.ServerError
    }
}

/**
 * The [DomainState.Error] for a query the server answered with GraphQL `errors`.
 * [message] is the joined server text. When the screen replaces it with the
 * reason's own wording, the server text stays in [DomainState.Error.detail]
 * ("Show details"); for [ErrorReason.ServerError] it is the screen text itself.
 */
fun graphQlError(message: String, errors: List<Error>?): DomainState.Error {
    val reason = classifyErrorReason(exception = null, errors = errors)
    return DomainState.Error(
        message = message,
        detail = if (reason == ErrorReason.ServerError) null else message,
        reason = reason,
    )
}

/** What Unraid's resolvers throw when the service behind a domain is off (Docker: missing socket; VMs: no hypervisor). */
private val SERVICE_OFF_MARKERS = listOf("Docker socket unavailable", "VMs are not available")

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

/** Plain sentence shown under "Can't reach the server" when the transport itself failed (#266). */
const val UNREACHABLE_MESSAGE = "Check that the server is online and the address is correct."

/**
 * True when this failure means the server could not be reached at all: an
 * [ApolloNetworkException] whose cause chain holds a connectivity error (no
 * DNS answer, refused or unroutable connection, timeout). TLS and parsing
 * failures are deliberately not matched: their text is the diagnosis and the
 * user needs it (certificate dialogs, #217).
 */
fun Throwable.isServerUnreachable(): Boolean {
    if (this !is ApolloNetworkException) return false
    val chain = generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    if (chain.any { it is SSLException || it is CertificateException }) return false
    return chain.any {
        it is UnknownHostException || it is SocketException || it is InterruptedIOException ||
            it::class.simpleName == "GaiException" // android.system.GaiException, not on the JVM test classpath
    }
}

private const val MAX_CAUSE_DEPTH = 10

/**
 * The [DomainState.Error] for a failed query. An unreachable server gets the
 * plain [UNREACHABLE_MESSAGE] with the raw text kept in
 * [DomainState.Error.detail]; every other failure (HTTP status, GraphQL
 * errors, parsing, certificates) keeps its specific [failureMessage].
 */
fun Throwable.toQueryError(fallback: String): DomainState.Error {
    val text = failureMessage(fallback)
    return if (isServerUnreachable()) DomainState.Error(UNREACHABLE_MESSAGE, detail = text) else DomainState.Error(text)
}

/**
 * A mutation (or other one-shot action) the server did not accept. [message]
 * is the honest, cause-carrying text ([failureMessage] for transport errors,
 * the joined GraphQL error messages otherwise) and is what the user sees.
 */
class ActionFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Throws [ActionFailedException] for a failed action response, using the same
 * classification as the query paths ([classifyResponse]). Apollo Kotlin 5's
 * `execute()` never throws for transport or GraphQL errors, so a caller that
 * does not look at the response sees every failure as success (ADR-0037).
 */
fun checkActionResponse(exception: ApolloException?, hasErrors: Boolean, errorMessage: String?) {
    when (val c = classifyResponse(exception, hasErrors, errorMessage, dataIsNull = false)) {
        is RespClass.Cert -> throw ActionFailedException(exception!!.failureMessage("Network error"), exception)
        is RespClass.Failed -> throw ActionFailedException(
            when (exception) {
                // Plain wording for the snackbar; the technical cause stays on the exception.
                is ApolloHttpException -> "the server answered HTTP ${exception.statusCode}"
                is ApolloNetworkException -> "can't reach the server"
                else -> c.message
            },
            exception,
        )
        RespClass.Empty, RespClass.Ok -> Unit
    }
}

/** Returns this response, or throws [ActionFailedException] if it carries a transport or GraphQL error. */
fun <D : Operation.Data> ApolloResponse<D>.orThrow(): ApolloResponse<D> {
    checkActionResponse(exception, hasErrors(), errors?.joinToString { it.message })
    return this
}

/** Structured result of a connection test (consumed by the Add/Edit sheet). */
sealed interface TestOutcome {
    data object Ok : TestOutcome
    data class Failed(val message: String) : TestOutcome
    data class CertUntrusted(val sha256: String) : TestOutcome
    data class CertChanged(val pinned: String, val presented: String) : TestOutcome
}
