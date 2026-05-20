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

    /** Last poll failed and the transient-error tolerance has been exhausted. */
    data class Error(val message: String) : DomainState<Nothing>
}
