package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import io.github.nofuturekid.nova.data.api.CertIssue
import io.github.nofuturekid.nova.data.api.certIssue
import io.github.nofuturekid.nova.data.model.Access
import io.github.nofuturekid.nova.data.model.ApiVersion
import io.github.nofuturekid.nova.data.model.KeyAction
import io.github.nofuturekid.nova.data.model.Detection
import io.github.nofuturekid.nova.data.model.ServerCapabilities
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        if (issue != null) RespClass.Cert(issue)
        else RespClass.Failed(exception.redirectMessage() ?: exception.failureMessage("Network error"))
    }
    hasErrors -> RespClass.Failed(errorMessage ?: "Unknown GraphQL error")
    dataIsNull -> RespClass.Empty
    else -> RespClass.Ok
}

/**
 * Why a domain query failed, by the response it got. The first matching rule wins
 * (the bodies behind them were recorded from Unraid 7.4.0, API 4.37.11):
 *  1. a transport exception: [ErrorReason.Unreachable], or [ErrorReason.Redirected] when the
 *     server answered with a redirect, which the clients do not follow (ADR-0051);
 *  2. `UNAUTHENTICATED`: [ErrorReason.KeyRejected];
 *  3. `GRAPHQL_VALIDATION_FAILED` or "Cannot query field": [ErrorReason.ApiTooOld];
 *  4. `FORBIDDEN`: [ErrorReason.Forbidden];
 *  5. "Docker socket unavailable" or "VMs are not available": [ErrorReason.ServiceOff];
 *  6. anything else: [ErrorReason.ServerError].
 * Rules 2 to 4 are [classifyErrors], shared with the capability probe so the two
 * cannot disagree about what an `errors` array means.
 */
fun classifyErrorReason(exception: ApolloException?, errors: List<Error>?): ErrorReason {
    if (exception != null) {
        return if (exception.redirectMessage() != null) ErrorReason.Redirected else ErrorReason.Unreachable
    }
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

/** The statuses OkHttp follows by default; the clients here follow none of them (ADR-0051). */
private val REDIRECT_STATUSES = setOf(300, 301, 302, 303, 307, 308)

/**
 * The sentence for a server that answered with a redirect, or null when this failure is not one.
 * The clients do not follow redirects (the API key would go with them, ADR-0051), so a 3xx
 * arrives here as an [ApolloHttpException] with its `Location` header. The target is shown as
 * `scheme://host[:port]` only: the path says nothing the user can act on, a query may carry
 * tokens, and `user:password@` must never be echoed. A missing, relative or unparsable
 * `Location` gets the same advice without a target.
 */
fun ApolloException.redirectMessage(): String? {
    if (this !is ApolloHttpException || statusCode !in REDIRECT_STATUSES) return null
    val location = headers.firstOrNull { it.name.equals("Location", ignoreCase = true) }?.value
    val target = location?.trim()?.toHttpUrlOrNull()?.newBuilder()
        ?.username("")?.password("")?.encodedPath("/")?.query(null)?.fragment(null)
        ?.build()?.toString()?.removeSuffix("/")
    return if (target != null) {
        "The server redirects to $target. Edit the server and enter that address."
    } else {
        "The server redirects to another address, but doesn't say which. Check the address in this server's settings."
    }
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
    (this as? ApolloException)?.redirectMessage()?.let {
        return DomainState.Error(it, detail = text, reason = ErrorReason.Redirected)
    }
    return if (isServerUnreachable()) DomainState.Error(UNREACHABLE_MESSAGE, detail = text) else DomainState.Error(text)
}

/**
 * A mutation (or other one-shot action) the server did not accept. [message]
 * is the honest, cause-carrying text ([failureMessage] for transport errors,
 * the joined GraphQL error messages otherwise) and is what the user sees.
 */
class ActionFailedException(
    message: String,
    cause: Throwable? = null,
    /** The server's own wording when [message] replaces it with plain words (a refused permission); else null. */
    val detail: String? = null,
) : Exception(message, cause)

/**
 * The plain sentence for an action the server refused because of the key (#315, ADR-0047), or
 * null for any other failure. `FORBIDDEN` names the permission the control needs when
 * [operationName] is one of NOVA's mutations ([KeyAction.forOperation]); `UNAUTHENTICATED` says
 * the key was not accepted. This is the fallback path: while the key's access is unknown the
 * buttons are shown, and the server's refusal is explained here instead of "Forbidden resource".
 */
fun actionRefusalMessage(errors: List<Error>?, operationName: String?): String? {
    val list = errors.orEmpty()
    return when {
        list.any { it.code() == "UNAUTHENTICATED" } -> "the server didn't accept the API key"
        list.any { it.code() == "FORBIDDEN" } -> {
            val needs = operationName?.let { KeyAction.forOperation(it) }?.permission
            "your API key isn't allowed to do this" + (needs?.let { " (it needs $it)" } ?: "")
        }
        else -> null
    }
}

/**
 * Throws [ActionFailedException] for a failed action response, using the same
 * classification as the query paths ([classifyResponse]). Apollo Kotlin 5's
 * `execute()` never throws for transport or GraphQL errors, so a caller that
 * does not look at the response sees every failure as success (ADR-0037).
 */
fun checkActionResponse(
    exception: ApolloException?,
    hasErrors: Boolean,
    errorMessage: String?,
    errors: List<Error>? = null,
    operationName: String? = null,
) {
    when (val c = classifyResponse(exception, hasErrors, errorMessage, dataIsNull = false)) {
        is RespClass.Cert -> throw ActionFailedException(exception!!.failureMessage("Network error"), exception)
        is RespClass.Failed -> {
            val refusal = if (exception == null) actionRefusalMessage(errors, operationName) else null
            throw ActionFailedException(
                refusal ?: when (exception) {
                    // Plain wording for the snackbar; the technical cause stays on the exception.
                    is ApolloHttpException -> exception.redirectMessage()?.replaceFirstChar { it.lowercase() }
                        ?: "the server answered HTTP ${exception.statusCode}"
                    is ApolloNetworkException -> "can't reach the server"
                    else -> c.message
                },
                exception,
                detail = if (refusal != null) c.message else null,
            )
        }
        RespClass.Empty, RespClass.Ok -> Unit
    }
}

/** Returns this response, or throws [ActionFailedException] if it carries a transport or GraphQL error. */
fun <D : Operation.Data> ApolloResponse<D>.orThrow(): ApolloResponse<D> {
    checkActionResponse(exception, hasErrors(), errors?.joinToString { it.message }, errors, operation.name())
    return this
}

/** What the tested key may do, in the words of the Add server sheet. [text] is null when it is not known. */
sealed interface TestControl {
    val text: String?

    data object Unknown : TestControl { override val text: String? = null }

    data object ViewOnly : TestControl { override val text = "View only key: NOVA will hide controls" }

    data object Full : TestControl { override val text = "Full control" }

    /** Some controls: [areas] are the ones the key may use, e.g. "Docker", "VMs". */
    data class Some(val areas: List<String>) : TestControl {
        override val text = "Can control: ${areas.joinToString(", ")}"
    }

    companion object {
        /**
         * The summary of [caps]. Unknown as soon as one control's access is unknown: a half-known
         * key is not described, because a wrong "view only" would be worse than saying nothing.
         */
        fun of(caps: ServerCapabilities): TestControl {
            val all = KeyAction.entries.associateWith { caps.access(it) }
            return when {
                all.values.any { it == Access.Unknown } -> Unknown
                all.values.all { it == Access.Denied } -> ViewOnly
                all.values.all { it == Access.Allowed } -> Full
                else -> {
                    fun ok(a: KeyAction) = all[a] == Access.Allowed
                    Some(
                        listOfNotNull(
                            "Array".takeIf { ok(KeyAction.ControlArray) },
                            "Docker".takeIf { ok(KeyAction.ControlDocker) },
                            "VMs".takeIf { ok(KeyAction.ControlVms) },
                            when {
                                ok(KeyAction.UpdateNotifications) && ok(KeyAction.DeleteNotifications) -> "Notifications"
                                ok(KeyAction.UpdateNotifications) -> "Archive notifications"
                                ok(KeyAction.DeleteNotifications) -> "Delete notifications"
                                else -> null
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** Structured result of a connection test (consumed by the Add/Edit sheet). */
sealed interface TestOutcome {
    /**
     * The server answered and accepted the key (or could not say it did not). Carries what the
     * capability probe learned, so the Add server sheet can say what the key can do (#315).
     */
    data class Ok(
        val unraidVersion: String? = null,
        val apiVersionText: String? = null,
        val apiTooOld: Boolean = false,
        val control: TestControl = TestControl.Unknown,
    ) : TestOutcome {
        /** "Connected · Unraid 7.4.0 · API 4.37.11"; the parts that are not known are left out. */
        val title: String
            get() = listOfNotNull(
                "Connected",
                unraidVersion?.takeIf { it.isNotBlank() }?.let { "Unraid $it" },
                apiVersionText?.takeIf { it.isNotBlank() }?.let { "API ${ApiVersion.parse(it) ?: it}" },
            ).joinToString(" · ")

        /** The second line: the API warning if the server is too old, else what the key can do, else a plain "ready". */
        val detail: String
            get() = when {
                apiTooOld ->
                    "Connected, but this server's Unraid API is too old. NOVA needs 4.30.0 or newer (Unraid 7.2.5 or newer)."
                else -> control.text ?: "Server responded · ready to save"
            }
    }
    data class Failed(val message: String) : TestOutcome
    data class CertUntrusted(val sha256: String) : TestOutcome
    data class CertChanged(val pinned: String, val presented: String) : TestOutcome
}

/**
 * The connection-test result for a capability probe (ADR-0047). A rejected key fails the test with
 * the server's own wording; a transport failure keeps its certificate handling (an untrusted or
 * changed certificate opens the trust dialog, anything else is the exception's message); a server
 * that answered with an error NOVA does not classify shows that error's text.
 */
internal fun testOutcomeOf(probe: ProbeOutcome): TestOutcome = when (probe.detection) {
    Detection.KeyRejected -> TestOutcome.Failed("The server didn't accept this API key")
    Detection.Failed -> {
        val cause = probe.cause
        when (val issue = cause?.certIssue()) {
            is CertIssue.Untrusted -> TestOutcome.CertUntrusted(issue.sha256)
            is CertIssue.Changed -> TestOutcome.CertChanged(issue.pinned, issue.presented)
            null -> TestOutcome.Failed(
                cause?.failureMessage("Network error") ?: probe.failureText ?: "No response from server",
            )
        }
    }
    // Idle and Detecting are never returned by a probe; treat them as an answer without information.
    Detection.Done, Detection.Idle, Detection.Detecting -> {
        val caps = probe.toCapabilities(target = null)
        TestOutcome.Ok(
            unraidVersion = probe.unraidVersion,
            apiVersionText = probe.apiVersionText,
            apiTooOld = caps.apiTooOld,
            control = TestControl.of(caps),
        )
    }
}
