package io.github.nofuturekid.nova.data.update

import io.github.nofuturekid.nova.BuildConfig
import io.github.nofuturekid.nova.data.model.UpdateState
import io.github.nofuturekid.nova.data.model.UpdateStatus
import io.github.nofuturekid.nova.data.repository.SettingsRepository
import io.github.nofuturekid.nova.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Checks run in the application scope, not in the scope of the screen that
 * asked: leaving Settings while the check from app start is still in flight
 * must not lose its answer. A requester that goes away only stops waiting.
 *
 * The tracker also follows the "Include pre-releases" switch itself: when the
 * setting changes it checks again, so the answer for the new setting arrives
 * even if the user leaves Settings right after flipping the switch. Rapid
 * flipping makes one check, not one per flip (see [SETTING_DEBOUNCE_MS]).
 *
 * In memory only, on purpose: a stored result would have to be thrown away
 * on every app update and every switch change, and the check at app start
 * refreshes it anyway.
 */
@Singleton
class UpdateTracker internal constructor(
    private val scope: CoroutineScope,
    private val fetch: suspend (includePrereleases: Boolean) -> UpdateState,
    private val includePrereleases: Flow<Boolean>,
    private val recordCheck: suspend () -> Unit,
    followsTheSetting: Boolean,
) {
    @Inject
    constructor(
        updates: UpdateRepository,
        settings: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        scope = scope,
        fetch = updates::check,
        includePrereleases = settings.includePrereleases,
        recordCheck = { settings.setLastUpdateCheck(System.currentTimeMillis()) },
        followsTheSetting = BuildConfig.HAS_UPDATER, // the store flavor makes no update calls
    )

    /** A good result and the pre-release setting it was computed under. */
    private data class Held(val includePrereleases: Boolean, val result: UpdateState)

    private data class Snapshot(
        /** Number of the newest check started; only that check may store its result. */
        val run: Int = 0,
        val held: Held? = null,
        val failure: String? = null,
        /** The newest check has not finished yet. */
        val checking: Boolean = false,
    )

    private class Run(val number: Int, val includePrereleases: Boolean, val done: Deferred<Unit>)

    private val snapshot = MutableStateFlow(Snapshot())

    /** Decides whether a request joins the running check; guards [running]. */
    private val requests = Mutex()
    private var running: Run? = null

    /**
     * The latest known result for the setting that is current NOW. A result
     * computed under the other setting is not shown (it would offer a
     * pre-release to a stable-only user, or hide one from a beta user)
     * until the check that follows the switch has stored a fresh one.
     * [UpdateStatus.checking] says a check is running; the held result stays.
     */
    val status: Flow<UpdateStatus> = combine(snapshot, includePrereleases) { s, include ->
        UpdateStatus(
            latest = s.held?.takeIf { it.includePrereleases == include }?.result
                ?: UpdateState.Checking,
            failure = s.failure,
            checking = s.checking,
        )
    }.distinctUntilChanged()

    // Last in the property list: the launched job reads every property above.
    init {
        if (followsTheSetting) scope.launch { checkWhenTheSettingChanges() }
    }

    /**
     * Asks for a check and returns when it has stored its answer. The check
     * itself runs in the application scope: cancelling the caller only stops
     * the waiting. The pre-release setting is read here, when the check is
     * requested, never from UI state.
     *
     * A request made while a check under the SAME setting is running joins it
     * instead of making a second network call. A request under a different
     * setting starts a new check; the older one still ends, but its answer is
     * not stored (only the newest started check may store), so a result
     * computed under the old setting is never the one that stays. A failed
     * check keeps the last good result and records the failure.
     */
    suspend fun check() {
        val run = requests.withLock {
            val include = includePrereleases.first()
            running?.takeIf { it.includePrereleases == include && it.done.isActive }
                ?: start(include).also { running = it }
        }
        run.done.await()
    }

    private fun start(include: Boolean): Run {
        val number = snapshot
            .updateAndGet { it.copy(run = it.run + 1, failure = null, checking = true) }.run
        return Run(number, include, scope.async { execute(number, include) })
    }

    /**
     * A change of the setting (not the value it starts with, and not another
     * write to the store that leaves it as it was) asks for a check. The
     * debounce is what bounds the network calls: only the value the user stops
     * on is checked. (collectLatest alone would not bound them, a check keeps
     * running when the one waiting for it is replaced; it is here so a slow
     * check under the old setting does not delay the one for the new setting.)
     */
    @OptIn(FlowPreview::class)
    private suspend fun checkWhenTheSettingChanges() {
        includePrereleases.distinctUntilChanged().drop(1).debounce(SETTING_DEBOUNCE_MS).collectLatest { check() }
    }

    private suspend fun execute(run: Int, include: Boolean) {
        try {
            val result = fetch(include)
            snapshot.update { s ->
                when {
                    s.run != run -> s
                    result is UpdateState.Error -> s.copy(failure = result.message)
                    else -> s.copy(held = Held(include, result), failure = null)
                }
            }
            recordCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never leave the row on "Checking…" or crash the awaiting screens.
            // The held result stays; only the newest check may report.
            snapshot.update { if (it.run == run) it.copy(failure = e.message ?: "Network error") else it }
        } finally {
            // Also on cancellation or an exception: the flag must not stay on.
            snapshot.update { if (it.run == run) it.copy(checking = false) else it }
        }
    }

    internal companion object {
        /** Quiet time after the last flip of the switch before the check starts. */
        const val SETTING_DEBOUNCE_MS = 300L
    }
}
