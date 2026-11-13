package io.github.nofuturekid.nova.data.update

import io.github.nofuturekid.nova.data.model.UpdateInfo
import io.github.nofuturekid.nova.data.model.UpdateState
import io.github.nofuturekid.nova.data.model.UpdateStatus
import io.github.nofuturekid.nova.data.update.UpdateRepository.Companion.evaluate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Since 0.1.47 the update notice and its sheet are the only way users learn
 * about updates (ADR-0046). On 2026-11-13 a phone showed the Overview notice
 * for v0.1.46 while Settings offered v0.1.47-beta1, and Settings itself fell
 * back to v0.1.46 after being reopened. These tests pin the rules that keep
 * every surface on ONE result: the tracker's status.
 *
 * The scenario is the one from that day: v0.1.46-beta1 installed, the stable
 * v0.1.46 and the newer pre-release v0.1.47-beta1 both published.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateTrackerTest {

    private val installed = "0.1.46-beta1"

    private fun release(tag: String, prerelease: Boolean) = """
        {"tag_name":"$tag","draft":false,"prerelease":$prerelease,
         "html_url":"https://github.com/hellwand/nova/releases/tag/$tag",
         "body":"notes","published_at":"2026-11-13T10:00:00Z","assets":[]}
    """.trimIndent()

    private val bothReleases =
        "[" + release("v0.1.47-beta1", true) + "," + release("v0.1.46", false) + "]"

    /** What GitHub plus the real evaluation answer for each switch position. */
    private val github: suspend (Boolean) -> UpdateState = { include ->
        evaluate(bothReleases, include, installed)
    }

    private class Fixture(
        scope: CoroutineScope,
        val include: MutableStateFlow<Boolean>,
        fetch: suspend (Boolean) -> UpdateState,
        followsTheSetting: Boolean,
    ) {
        var recorded = 0
        var fetches = 0
        var recordFails: Exception? = null
        val tracker = UpdateTracker(
            scope, { inc -> fetches++; fetch(inc) }, include,
            { recordFails?.let { throw it }; recorded++ }, followsTheSetting,
        )
        suspend fun status(): UpdateStatus = tracker.status.first()
    }

    /** The tracker runs its checks in the application scope; here a scope of the test that outlives the requesters. */
    private fun TestScope.fixture(
        includePrereleases: Boolean,
        followsTheSetting: Boolean = false,
        fetch: suspend (Boolean) -> UpdateState,
    ) = Fixture(backgroundScope, MutableStateFlow(includePrereleases), fetch, followsTheSetting)

    private fun tagOf(state: UpdateState): String {
        assertTrue("expected Available, was $state", state is UpdateState.Available)
        return (state as UpdateState.Available).info.tag
    }

    /**
     * WHY: the bug. Settings showed the fresh result and the notice kept an
     * older one. Two separate consumers (standing in for the two view models
     * that collect the status) are already listening when ONE of them asks
     * for a check; both must then see that check's release.
     */
    @Test
    fun aCheckStoresOneResultThatEverySurfaceReads() = runTest {
        val f = fixture(includePrereleases = true, fetch = github)
        val notice = f.tracker.status.stateIn(backgroundScope, SharingStarted.Eagerly, UpdateStatus())
        val settingsLine = f.tracker.status.stateIn(backgroundScope, SharingStarted.Eagerly, UpdateStatus())

        f.tracker.check() // asked from the Settings side only
        runCurrent()

        assertEquals("v0.1.47-beta1", tagOf(settingsLine.value.latest))
        assertEquals(settingsLine.value, notice.value)
        assertEquals("v0.1.47-beta1", notice.value.notice(dismissedTag = null)?.tag)
    }

    /**
     * WHY: the Settings screen started its check from a UI state that still
     * held the default "off" before the real setting had loaded, so with the
     * switch on it asked for stable releases only (and showed v0.1.46).
     * The tracker must read the setting when the check runs.
     */
    @Test
    fun theCheckUsesTheSettingAsItIsWhenItRuns() = runTest {
        val asked = mutableListOf<Boolean>()
        val f = fixture(includePrereleases = false) { include -> asked += include; UpdateState.UpToDate }

        f.include.value = true
        f.tracker.check()
        f.include.value = false
        f.tracker.check()

        assertEquals(listOf(true, false), asked)
    }

    /**
     * WHY: a result computed with pre-releases on must not stay on screen
     * after the user turns them off (a stable-only user would be offered a
     * beta), and the stable result must not hide the beta after turning them
     * on. The old result is hidden at once; the fresh one replaces it.
     */
    @Test
    fun switchingPrereleasesNeverLeavesTheOtherSettingsResultOnScreen() = runTest {
        val f = fixture(includePrereleases = true, fetch = github)
        f.tracker.check()
        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))

        f.include.value = false
        val afterSwitchOff = f.status()
        assertEquals(UpdateState.Checking, afterSwitchOff.latest)
        assertNull(afterSwitchOff.notice(null))
        f.tracker.check()
        assertEquals("v0.1.46", tagOf(f.status().latest))

        f.include.value = true
        assertEquals(UpdateState.Checking, f.status().latest)
        assertNull(f.status().notice(null))
        f.tracker.check()
        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))
    }

    /**
     * WHY: a user who never switched pre-releases on must never be offered
     * one, even when the only newer release is a pre-release.
     */
    @Test
    fun aStableOnlyUserIsNeverOfferedAPrerelease() = runTest {
        val onlyBeta = "[" + release("v0.1.47-beta1", true) + "]"
        val f = fixture(includePrereleases = false) { include -> evaluate(onlyBeta, include, "0.1.46") }

        f.tracker.check()

        assertEquals(UpdateState.UpToDate, f.status().latest)
        assertNull(f.status().notice(null))
    }

    /**
     * WHY: no notice for a release that is not newer than the installed
     * build (a beta user who already runs the newest pre-release).
     */
    @Test
    fun aResultThatIsNotNewerThanTheInstalledVersionShowsNoNotice() = runTest {
        val f = fixture(includePrereleases = true) { include ->
            evaluate(bothReleases, include, "0.1.47-beta1")
        }

        f.tracker.check()

        assertEquals(UpdateState.UpToDate, f.status().latest)
        assertNull(f.status().notice(null))
    }

    /**
     * WHY: the user turns "Include pre-releases" on while the check made
     * under the old setting is still in flight. That check is the slow one
     * and answers last; its result belongs to the old setting and must not
     * be the one that stays. The check under the new setting is the one the
     * user is waiting for.
     */
    @Test
    fun aSettingChangeMidFlightEndsWithTheResultOfTheNewSetting() = runTest {
        val answers = ArrayDeque<CompletableDeferred<UpdateState>>()
        val f = fixture(includePrereleases = false) { answers.removeFirst().await() }
        val slowOld = CompletableDeferred<UpdateState>().also { answers.addLast(it) }
        val fastNew = CompletableDeferred<UpdateState>().also { answers.addLast(it) }

        val older = launch { f.tracker.check() }
        runCurrent()
        f.include.value = true
        val newer = launch { f.tracker.check() }
        runCurrent()
        assertEquals("a second call under the new setting", 2, f.fetches)
        fastNew.complete(github(true))
        runCurrent()
        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))

        slowOld.complete(github(false))
        runCurrent()
        older.join()
        newer.join()

        val end = f.status()
        assertEquals("v0.1.47-beta1", tagOf(end.latest))
        assertEquals("the newest check is done, the old one must not keep the flag on", false, end.checking)
    }

    /**
     * WHY: the opposite order of the test above. The superseded check ends
     * FIRST while the check under the new setting still runs. Its end must
     * not clear the in-progress flag of the newer check (the row would stop
     * saying "Checking…" too early) and its result must not be stored.
     */
    @Test
    fun aSupersededCheckThatEndsFirstDoesNotClearTheFlagOfTheCheckStillRunning() = runTest {
        val answers = ArrayDeque<CompletableDeferred<UpdateState>>()
        val f = fixture(includePrereleases = false) { answers.removeFirst().await() }
        val slowOld = CompletableDeferred<UpdateState>().also { answers.addLast(it) }
        val fastNew = CompletableDeferred<UpdateState>().also { answers.addLast(it) }

        val older = launch { f.tracker.check() }
        runCurrent()
        f.include.value = true
        val newer = launch { f.tracker.check() }
        runCurrent()
        slowOld.complete(github(false))
        runCurrent()
        older.join()

        val whileNewIsRunning = f.status()
        assertEquals("the newer check is still running", true, whileNewIsRunning.checking)
        assertEquals("the old setting's result is not stored", UpdateState.Checking, whileNewIsRunning.latest)

        fastNew.complete(github(true))
        runCurrent()
        newer.join()

        val end = f.status()
        assertEquals(false, end.checking)
        assertEquals("v0.1.47-beta1", tagOf(end.latest))
    }

    /**
     * WHY: if the check itself breaks (not a clean "Error" answer but an
     * exception, e.g. the write of the check time fails), the row used to
     * stay on "Checking…" forever and the exception reached every screen
     * that waited, which crashes the app. The requester returns normally,
     * the progress ends, the failure is shown and the last good result stays.
     */
    @Test
    fun aCheckThatThrowsEndsTheProgressKeepsTheResultAndDoesNotReachTheRequester() = runTest {
        var broken = false
        val f = fixture(includePrereleases = true) { include ->
            if (broken) throw IOException("connection reset") else github(include)
        }
        f.tracker.check()

        broken = true
        f.tracker.check() // must return normally

        val failed = f.status()
        assertEquals(false, failed.checking)
        assertEquals("connection reset", failed.failure)
        assertEquals("v0.1.47-beta1", tagOf(failed.latest))

        broken = false
        f.tracker.check()
        assertNull(f.status().failure)
    }

    /** WHY: same as above for the other step of a check, and for an exception without a message. */
    @Test
    fun aFailingWriteOfTheCheckTimeDoesNotLeaveTheRowOnChecking() = runTest {
        val f = fixture(includePrereleases = true, fetch = github)
        f.recordFails = IllegalStateException()

        f.tracker.check() // must return normally

        val s = f.status()
        assertEquals(false, s.checking)
        assertEquals("Network error", s.failure)
    }

    /**
     * WHY: the app-start check is still in flight when the user opens and
     * leaves Settings. Everything a screen starts used to be cancelled with
     * the screen, and the answer was thrown away. Leaving a screen must not
     * cancel a check: the result is stored although nobody is waiting.
     */
    @Test
    fun aCheckStillStoresItsResultWhenTheRequesterIsCancelledMidFlight() = runTest {
        val answer = CompletableDeferred<UpdateState>()
        val f = fixture(includePrereleases = true) { answer.await() }

        val screen = launch { f.tracker.check() }
        runCurrent()
        screen.cancel()
        runCurrent()
        answer.complete(github(true))
        runCurrent()

        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))
        assertEquals("v0.1.47-beta1", f.status().notice(null)?.tag)
        assertEquals(1, f.recorded)
    }

    /**
     * WHY: app start and the Settings screen both ask within moments. The
     * same setting means the same answer: the second request waits for the
     * running check instead of making a second network call (GitHub's
     * unauthenticated rate limit is per hour).
     */
    @Test
    fun twoRequestsInARowMakeOneNetworkCall() = runTest {
        val answer = CompletableDeferred<UpdateState>()
        val f = fixture(includePrereleases = true) { answer.await() }

        val first = launch { f.tracker.check() }
        runCurrent()
        val second = launch { f.tracker.check() }
        runCurrent()
        answer.complete(github(true))
        runCurrent()
        first.join()
        second.join()

        assertEquals(1, f.fetches)
        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))
    }

    /**
     * WHY: a tap on "Check now" looked like nothing happened once the row
     * kept the last result. While a check runs the status says so, with the
     * held result still in place for the Overview notice; the flag is gone
     * when the result is in.
     */
    @Test
    fun aRunningCheckIsVisibleWhileTheHeldResultStays() = runTest {
        val next = ArrayDeque<CompletableDeferred<UpdateState>>()
        val f = fixture(includePrereleases = true) { next.removeFirst().await() }
        val first = CompletableDeferred<UpdateState>().also { next.addLast(it) }
        val second = CompletableDeferred<UpdateState>().also { next.addLast(it) }
        assertEquals(false, f.status().checking)

        val a = launch { f.tracker.check() }
        runCurrent()
        assertEquals(true, f.status().checking)
        first.complete(github(true))
        runCurrent()
        a.join()
        assertEquals(false, f.status().checking)

        val b = launch { f.tracker.check() }
        runCurrent()
        val during = f.status()
        assertEquals(true, during.checking)
        assertEquals("v0.1.47-beta1", during.notice(null)?.tag)
        second.complete(UpdateState.UpToDate)
        runCurrent()
        b.join()
        val after = f.status()
        assertEquals(false, after.checking)
        assertEquals(UpdateState.UpToDate, after.latest)
    }

    /**
     * WHY: a failed check must end the "Checking…" state (otherwise the row
     * would say so forever) and still keep the last good result.
     */
    @Test
    fun aFailedCheckEndsTheProgressAndKeepsTheLastGoodResult() = runTest {
        val next = ArrayDeque<CompletableDeferred<UpdateState>>()
        val f = fixture(includePrereleases = true) { next.removeFirst().await() }
        next.addLast(CompletableDeferred(github(true)))
        f.tracker.check()
        val failing = CompletableDeferred<UpdateState>().also { next.addLast(it) }

        val b = launch { f.tracker.check() }
        runCurrent()
        assertEquals(true, f.status().checking)
        failing.complete(UpdateState.Error("Network error"))
        runCurrent()
        b.join()

        val s = f.status()
        assertEquals(false, s.checking)
        assertEquals("Network error", s.failure)
        assertEquals("v0.1.47-beta1", tagOf(s.latest))
    }

    /**
     * WHY: a flaky network must not make a known update disappear from the
     * notice. The last good result stays; Settings still learns that the
     * latest check failed, and the next good check clears the failure.
     */
    @Test
    fun aFailedCheckKeepsTheLastGoodResultAndReportsTheFailure() = runTest {
        var answer: UpdateState = github(true)
        val f = fixture(includePrereleases = true) { answer }
        f.tracker.check()

        answer = UpdateState.Error("GitHub HTTP 403")
        f.tracker.check()
        val failed = f.status()
        assertEquals("v0.1.47-beta1", tagOf(failed.latest))
        assertEquals("v0.1.47-beta1", failed.notice(null)?.tag)
        assertEquals("GitHub HTTP 403", failed.failure)

        answer = github(true)
        f.tracker.check()
        assertNull(f.status().failure)
    }

    /** WHY: with nothing known yet a failure must still be visible, not "Checking…" forever. */
    @Test
    fun aFailureWithoutAnyGoodResultIsReported() = runTest {
        val f = fixture(includePrereleases = true) { UpdateState.Error("Network error") }

        f.tracker.check()

        val s = f.status()
        assertEquals(UpdateState.Checking, s.latest)
        assertEquals("Network error", s.failure)
        assertNull(s.notice(null))
    }

    /**
     * WHY: a good result computed under the other setting must not be
     * "kept" by a failed check that follows the switch.
     */
    @Test
    fun aFailedCheckAfterTheSwitchDoesNotBringBackTheOtherSettingsResult() = runTest {
        var answer: UpdateState = github(true)
        val f = fixture(includePrereleases = true) { answer }
        f.tracker.check()

        f.include.value = false
        answer = UpdateState.Error("Network error")
        f.tracker.check()

        assertEquals(UpdateState.Checking, f.status().latest)
        assertNull(f.status().notice(null))
    }

    /** WHY: "Last check" in Settings must move after every check, good or failed. */
    @Test
    fun everyCheckRecordsItsTime() = runTest {
        var answer: UpdateState = UpdateState.UpToDate
        val f = fixture(includePrereleases = false) { answer }

        f.tracker.check()
        answer = UpdateState.Error("Network error")
        f.tracker.check()

        assertEquals(2, f.recorded)
    }

    /**
     * WHY: the user flips "Include pre-releases" and leaves Settings at
     * once. Nobody asks for a check any more, yet the Overview notice must
     * get the answer for the new setting: the tracker checks by itself, once
     * the user has stopped flipping.
     */
    @Test
    fun aChangeOfTheSettingStartsACheckWithoutAnyRequester() = runTest {
        val f = fixture(includePrereleases = false, followsTheSetting = true, fetch = github)
        f.tracker.check()
        assertEquals("v0.1.46", tagOf(f.status().latest))

        f.include.value = true
        runCurrent()
        advanceTimeBy(UpdateTracker.SETTING_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals("not before the user has stopped flipping", 1, f.fetches)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(2, f.fetches)
        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))
        assertEquals("v0.1.47-beta1", f.status().notice(null)?.tag)
    }

    /**
     * WHY: every call counts against GitHub's unauthenticated rate limit.
     * Flipping the switch several times in a row makes ONE call, for the
     * position the user stopped on.
     */
    @Test
    fun flippingTheSettingQuicklyMakesOneNetworkCall() = runTest {
        val asked = mutableListOf<Boolean>()
        val f = fixture(includePrereleases = false, followsTheSetting = true) { include ->
            asked += include
            github(include)
        }
        runCurrent()

        for (position in listOf(true, false, true)) {
            f.include.value = position
            advanceTimeBy(UpdateTracker.SETTING_DEBOUNCE_MS / 3)
            runCurrent()
        }
        advanceTimeBy(UpdateTracker.SETTING_DEBOUNCE_MS)
        runCurrent()

        assertEquals(listOf(true), asked)
        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))
    }

    /**
     * WHY: no check without a real change. The value the setting starts
     * with is not a change, and the store re-emits the setting whenever ANY
     * setting is written (for example the dismissed tag that the switch
     * itself resets).
     */
    @Test
    fun noCheckIsStartedWithoutAChangeOfTheSetting() = runTest {
        val store = MutableSharedFlow<Boolean>(replay = 1).also { it.tryEmit(false) }
        var fetches = 0
        UpdateTracker(
            backgroundScope, { fetches++; UpdateState.UpToDate }, store, {}, followsTheSetting = true,
        )
        runCurrent()
        advanceTimeBy(10 * UpdateTracker.SETTING_DEBOUNCE_MS)
        runCurrent()
        assertEquals("the starting value is no change", 0, fetches)

        store.emit(false) // another key was written, the setting is as before
        advanceTimeBy(10 * UpdateTracker.SETTING_DEBOUNCE_MS)
        runCurrent()
        assertEquals("a write that leaves the setting alone is no change", 0, fetches)

        store.emit(true)
        advanceTimeBy(UpdateTracker.SETTING_DEBOUNCE_MS)
        runCurrent()
        assertEquals("a real change is", 1, fetches)
    }

    /** WHY: the store flavor has no updater and must make no update calls, not even after a switch change. */
    @Test
    fun withoutAnUpdaterAChangeOfTheSettingMakesNoCall() = runTest {
        val f = fixture(includePrereleases = false, followsTheSetting = false, fetch = github)
        runCurrent()

        f.include.value = true
        advanceTimeBy(10 * UpdateTracker.SETTING_DEBOUNCE_MS)
        runCurrent()

        assertEquals(0, f.fetches)
    }

    /**
     * WHY: the notice is one decision made in one place: nothing for "up to
     * date" or while checking, nothing for a tag the user dismissed, and a
     * newer tag is offered again after an earlier one was dismissed.
     */
    @Test
    fun theNoticeFollowsTheResultAndTheDismissal() {
        fun info(tag: String) = UpdateInfo(
            version = tag.removePrefix("v"), tag = tag, releaseUrl = "https://github.com/hellwand/nova/releases/tag/$tag",
            isPrerelease = false, releaseNotes = "",
        )
        assertNull(UpdateStatus(UpdateState.UpToDate).notice(null))
        assertNull(UpdateStatus(UpdateState.Checking).notice(null))
        val available = UpdateStatus(UpdateState.Available(info("v0.1.47")))
        assertNotNull(available.notice(null))
        assertNull(available.notice(dismissedTag = "v0.1.47"))
        assertEquals("v0.1.47", available.notice(dismissedTag = "v0.1.46")?.tag)
    }
}
