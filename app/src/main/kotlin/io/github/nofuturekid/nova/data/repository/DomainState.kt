package io.github.nofuturekid.nova.data.repository

/**
 * Generic state envelope for a single per-domain poll stream — introduced
 * by the per-tab polling split (ADR-0017) to replace the prior single-global
 * snapshot state.
 *
 * Each domain (info, metrics, array, docker, vms) exposes a
 * [kotlinx.coroutines.flow.Flow] of [DomainState] whose lifecycle is gated on
 * whether the UI for that domain is currently visible. UI consumers read the
 * matching [DomainState] and render Loading / NoServer / Content / Error in
 * the usual way.
 */
sealed interface DomainState<out T> {
    /** No server selected yet — applies before the user has added one. */
    data object NoServer : DomainState<Nothing>

    /** Polling started, no result yet. */
    data object Loading : DomainState<Nothing>

    /**
     * Latest successful payload. [serverBaseUrl] is filled for streams whose
     * UI needs it (Docker, for container-icon URL resolution); other streams
     * may leave it empty.
     */
    data class Content<T>(val value: T, val serverBaseUrl: String = "") : DomainState<T>

    /**
     * Last poll failed and the transient-error tolerance has been exhausted.
     * [message] is what the user reads. [detail] is the raw technical text
     * (exception message plus root cause) kept for diagnosis when [message]
     * is a plain-language replacement; null when [message] already is the
     * honest, specific text. [reason] says why the poll failed so the screen
     * can name the real cause instead of always "Can't reach the server"; the
     * default keeps the meaning every producer had before it existed. For a
     * reason whose screen text replaces the server's wording (everything but
     * [ErrorReason.Unreachable] and [ErrorReason.ServerError]) the raw server
     * text is kept in [detail].
     */
    data class Error(
        val message: String,
        val detail: String? = null,
        val reason: ErrorReason = ErrorReason.Unreachable,
    ) : DomainState<Nothing>
}

/**
 * Why a domain query failed (#247, #217). Classified from the response by
 * [classifyErrorReason]; the screens show a matching title and next step.
 */
enum class ErrorReason {
    /** Transport, certificate or non-GraphQL HTTP failure: the server did not answer properly. */
    Unreachable,

    /** The server answered UNAUTHENTICATED: it did not accept the API key. */
    KeyRejected,

    /** The server answered FORBIDDEN: the key may not read this domain. */
    Forbidden,

    /** The query failed GraphQL validation: the server's Unraid API is older than this screen needs. */
    ApiTooOld,

    /** The server is fine but the service behind this domain (Docker, VM manager) is turned off. */
    ServiceOff,

    /**
     * The server answered with a redirect (3xx). The app does not follow it, because the API key
     * would go with it (ADR-0051); the message names where it points so the user can enter that
     * address. The server is reachable, so this is not a [isConnectionProblem].
     */
    Redirected,

    /** Any other error the server reported; its own text is shown. */
    ServerError,
    ;

    /** True when the server itself cannot be used: the top bar shows it, other reasons leave the pill alone. */
    val isConnectionProblem: Boolean get() = this == Unreachable || this == KeyRejected
}
