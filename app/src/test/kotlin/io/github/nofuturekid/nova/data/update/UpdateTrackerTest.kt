package io.github.nofuturekid.nova.data.update

import io.github.nofuturekid.nova.data.model.UpdateInfo
import io.github.nofuturekid.nova.data.model.UpdateState
import io.github.nofuturekid.nova.data.model.UpdateStatus
import io.github.nofuturekid.nova.data.update.UpdateRepository.Companion.evaluate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val include: MutableStateFlow<Boolean>,
        fetch: suspend (Boolean) -> UpdateState,
    ) {
        var recorded = 0
        val tracker = UpdateTracker(fetch, include) { recorded++ }
        suspend fun status(): UpdateStatus = tracker.status.first()
    }

    private fun fixture(
        includePrereleases: Boolean,
        fetch: suspend (Boolean) -> UpdateState,
    ) = Fixture(MutableStateFlow(includePrereleases), fetch)

    private fun tagOf(state: UpdateState): String {
        assertTrue("expected Available, was $state", state is UpdateState.Available)
        return (state as UpdateState.Available).info.tag
    }

    /**
     * WHY: the bug. Settings showed the fresh result and the notice kept an
     * older one. After a check, whoever asks next (the notice, the Settings
     * line, a Settings screen opened again) must get the same release.
     */
    @Test
    fun aCheckStoresOneResultThatEverySurfaceReads() = runTest {
        val f = fixture(includePrereleases = true, fetch = github)

        f.tracker.check()

        val settingsLine = f.status()
        val noticeAfterReopening = f.status()
        assertEquals("v0.1.47-beta1", tagOf(settingsLine.latest))
        assertEquals(settingsLine, noticeAfterReopening)
        assertEquals("v0.1.47-beta1", settingsLine.notice(dismissedTag = null)?.tag)
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
     * WHY: two checks can be in flight (app start and the Settings screen,
     * or a switch change and "Check now"). The one started later is the one
     * the user asked for last; a slow older answer arriving afterwards must
     * not replace it.
     */
    @Test
    fun anOlderCheckNeverOverridesANewerFreshOne() = runTest {
        val answers = ArrayDeque<CompletableDeferred<UpdateState>>()
        val f = fixture(includePrereleases = true) { answers.removeFirst().await() }
        val slowOld = CompletableDeferred<UpdateState>().also { answers.addLast(it) }
        val fastNew = CompletableDeferred<UpdateState>().also { answers.addLast(it) }

        val older = launch { f.tracker.check() }
        runCurrent()
        val newer = launch { f.tracker.check() }
        runCurrent()
        fastNew.complete(github(true))
        runCurrent()
        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))

        slowOld.complete(github(false))
        runCurrent()
        older.join()
        newer.join()

        assertEquals("v0.1.47-beta1", tagOf(f.status().latest))
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
