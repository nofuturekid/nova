package io.github.nofuturekid.nova.ui.screens.main

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.BuildConfig
import io.github.nofuturekid.nova.data.alerts.AlertNotifier
import io.github.nofuturekid.nova.data.alerts.AlertScheduler
import io.github.nofuturekid.nova.data.alerts.PendingNavigation
import io.github.nofuturekid.nova.data.alerts.SettingsAlertStateStore
import io.github.nofuturekid.nova.data.api.ApolloClientFactory
import io.github.nofuturekid.nova.data.demo.DemoUnraid
import io.github.nofuturekid.nova.data.local.ApiKeyStore
import io.github.nofuturekid.nova.data.local.SettingsStore
import io.github.nofuturekid.nova.data.model.UpdateState
import io.github.nofuturekid.nova.data.repository.CapabilityRepository
import io.github.nofuturekid.nova.data.repository.ServerRepository
import io.github.nofuturekid.nova.data.repository.SettingsRepository
import io.github.nofuturekid.nova.data.repository.UnraidGateway
import io.github.nofuturekid.nova.data.repository.UnraidRepository
import io.github.nofuturekid.nova.data.update.UpdateRepository
import io.github.nofuturekid.nova.data.update.UpdateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * WHY: a user who opens the app must see a newer release on the first screen,
 * without having to visit Settings. On 2026-11-13 a phone with v0.1.48-beta1
 * installed was brought back to the foreground ~20 minutes after the last use,
 * a few minutes after v0.1.48 was published. The Overview showed no notice for
 * a minute; only opening Settings (which runs its own check) made it appear.
 *
 * The cause: the app-start check ran once, in the init block of the
 * [MainViewModel], and a ViewModel that outlives a trip to the background is
 * not created again. The tracker kept holding the answer from before the
 * release ("up to date") for as long as the process lived, and nothing asked
 * again when the app came back. The screen already tells the ViewModel when it
 * becomes visible ([MainViewModel.setAppVisible]); that is the moment to ask.
 *
 * Runs the real [MainViewModel] (Robolectric, real stores) with the tracker's
 * network call replaced by a fake GitHub that evaluates real release JSON with
 * the real version comparison, so it also pins that v0.1.48 is newer than the
 * installed v0.1.48-beta1.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpdateCheckOnForegroundTest {

    private val installed = "0.1.48-beta1"
    private val scope = CoroutineScope(Dispatchers.Default)

    /** What GitHub lists right now; the test publishes v0.1.48 by changing it. */
    @Volatile private var releases = "[]"
    private val fetches = AtomicInteger()

    private fun stable(tag: String) = """
        [{"tag_name":"$tag","draft":false,"prerelease":false,
          "html_url":"https://github.com/hellwand/nova/releases/tag/$tag",
          "body":"notes","published_at":"2026-11-13T18:32:00Z","assets":[]}]
    """.trimIndent()

    private lateinit var tracker: UpdateTracker
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        // The store flavor makes no update calls at all (BuildConfig.HAS_UPDATER).
        assumeTrue(BuildConfig.HAS_UPDATER)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        val alertScheduler = AlertScheduler(ctx, store, SettingsAlertStateStore(store), AlertNotifier(ctx))
        val servers = ServerRepository(store, ApiKeyStore(ctx), alertScheduler)
        tracker = UpdateTracker(
            scope = scope,
            fetch = { include -> fetches.incrementAndGet(); UpdateRepository.evaluate(releases, include, installed) },
            includePrereleases = flowOf(true), // "Include pre-releases" is on, as on the phone
            recordCheck = {},
            followsTheSetting = false,
        )
        vm = MainViewModel(
            servers = servers,
            unraid = UnraidGateway(servers, UnraidRepository(servers, ApolloClientFactory()), DemoUnraid(servers, scope)),
            settings = SettingsRepository(store),
            updateTracker = tracker,
            caps = CapabilityRepository(scope, flowOf(null), { null }, { error("no server, no probe") }),
            alertScheduler = alertScheduler,
            pendingNavigation = PendingNavigation(),
        )
    }

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun waitFor(what: String, cond: suspend () -> Boolean) {
        try {
            withTimeout(5_000) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what (fetches=${fetches.get()})", e)
        }
    }

    private suspend fun noticeTag(): String? = tracker.status.first().notice(dismissedTag = null)?.tag

    @Test fun aReleaseThatAppearsWhileTheAppIsInTheBackgroundIsOfferedWhenItComesBack() = runBlocking {
        releases = "[]" // the phone's last use: v0.1.48-beta1 is the newest release
        vm.setAppVisible(true) // the app is opened
        waitFor("the first check to finish") { fetches.get() >= 1 && !tracker.status.first().checking }
        assertNull("nothing newer is out yet", noticeTag())

        vm.setAppVisible(false) // the app goes to the background, the process stays alive
        releases = stable("v0.1.48") // the stable release is published meanwhile
        vm.setAppVisible(true) // the user opens the app again, on the Overview

        waitFor("the notice for v0.1.48 without visiting Settings") { noticeTag() != null }
        assertEquals("v0.1.48", noticeTag())
    }

    @Test fun openingTheAppChecksExactlyOnce() = runBlocking {
        releases = stable("v0.1.48")
        vm.setAppVisible(true) // the screen reports ON_START once for a fresh ViewModel
        waitFor("the notice for v0.1.48") { noticeTag() != null }
        delay(300) // a second, duplicate check would have started by now
        assertEquals("v0.1.48", noticeTag())
        assertEquals("one check at open, not one from the ViewModel and another from the screen", 1, fetches.get())
    }

    @Test fun aStableReleaseIsNewerThanTheInstalledPreReleaseOfTheSameNumber() {
        val state = UpdateRepository.evaluate(stable("v0.1.48"), includePrereleases = true, currentVersion = installed)
        assertTrue("expected Available, was $state", state is UpdateState.Available)
    }
}
