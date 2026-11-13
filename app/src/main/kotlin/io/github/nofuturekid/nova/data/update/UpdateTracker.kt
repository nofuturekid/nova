package io.github.nofuturekid.nova.data.update

import io.github.nofuturekid.nova.data.model.UpdateState
import io.github.nofuturekid.nova.data.model.UpdateStatus
import io.github.nofuturekid.nova.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place that holds the latest known release. The automatic check
 * at app start, the check when Settings opens, "Check now" and the check
 * after the "Include pre-releases" switch all go through [check] and store
 * their result here; the Overview notice, its sheet and the Settings update
 * section all render [status]. Before this there were two copies (one per
 * ViewModel) that could not see each other.
 *
 * In memory only, on purpose: a stored result would have to be thrown away
 * on every app update and every switch change, and the check at app start
 * refreshes it anyway.
 */
@Singleton
class UpdateTracker internal constructor(
    private val fetch: suspend (includePrereleases: Boolean) -> UpdateState,
    private val includePrereleases: Flow<Boolean>,
    private val recordCheck: suspend () -> Unit,
) {
    @Inject
    constructor(updates: UpdateRepository, settings: SettingsRepository) : this(
        fetch = updates::check,
        includePrereleases = settings.includePrereleases,
        recordCheck = { settings.setLastUpdateCheck(System.currentTimeMillis()) },
    )

    /** A good result and the pre-release setting it was computed under. */
    private data class Held(val includePrereleases: Boolean, val result: UpdateState)

    private data class Snapshot(
        /** Number of the newest check started; only that check may store its result. */
        val run: Int = 0,
        val held: Held? = null,
        val failure: String? = null,
    )

    private val snapshot = MutableStateFlow(Snapshot())

    /**
     * The latest known result for the setting that is current NOW. A result
     * computed under the other setting is not shown (it would offer a
     * pre-release to a stable-only user, or hide one from a beta user)
     * until the check that follows the switch has stored a fresh one.
     */
    val status: Flow<UpdateStatus> = combine(snapshot, includePrereleases) { s, include ->
        UpdateStatus(
            latest = s.held?.takeIf { it.includePrereleases == include }?.result
                ?: UpdateState.Checking,
            failure = s.failure,
        )
    }.distinctUntilChanged()

    /**
     * Asks GitHub and stores the answer. The pre-release setting is read
     * here, when the check runs, never from UI state. A failed check keeps
     * the last good result and records the failure; a check that was
     * overtaken by a newer one stores nothing.
     */
    suspend fun check() {
        val run = snapshot.updateAndGet { it.copy(run = it.run + 1, failure = null) }.run
        val include = includePrereleases.first()
        val result = fetch(include)
        snapshot.update { s ->
            when {
                s.run != run -> s
                result is UpdateState.Error -> s.copy(failure = result.message)
                else -> s.copy(held = Held(include, result), failure = null)
            }
        }
        recordCheck()
    }
}
