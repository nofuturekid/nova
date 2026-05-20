package io.github.nofuturekid.nova.ui.screens.main

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.repository.ActiveServer
import io.github.nofuturekid.nova.data.repository.DomainState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triage #3 — switching the active server must never show the previous
 * server's data under the new server's name.
 *
 * The bug: [io.github.nofuturekid.nova.data.repository.UnraidRepository.domainStream]
 * restarts its inner poll loop on server change, but until the new server's
 * first poll completes, the gated `stateIn` (and any in-flight emission)
 * keeps serving server A's last [DomainState.Content]. The UI then briefly
 * renders A's array / containers / VMs / metrics labelled as server B — a
 * privacy/correctness defect.
 *
 * The fix keys a reset on SERVER IDENTITY (the active server's resolved base
 * URL), not on the per-tab gate: [MainViewModel.resetIfForeignServer] maps
 * any [DomainState.Content] whose `serverBaseUrl` != the current active URL
 * to [DomainState.Loading] until a matching Content arrives. These tests
 * drive the exact production transformation:
 *
 *  - `activeBaseUrl` is rebuilt here precisely as
 *    `UnraidRepository.activeBaseUrl` (map → null when no server/blank key,
 *    then distinctUntilChanged).
 *  - the consumer applies the real [MainViewModel.resetIfForeignServer].
 *
 * Rule 9: the pre-fix reproduction (`withoutGuard_theBugReproduces`) is
 * asserted to STILL leak server A's Content under server B — so this suite
 * genuinely fails if the guard is removed or the identity key regresses.
 *
 * Collector style mirrors UpdateControllerTest (launch into a list +
 * advanceUntilIdle); JUnit + kotlinx-coroutines-test only, no new deps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleCrossServerTest {

    private val urlA = "http://server-a.local"
    private val urlB = "http://server-b.local"

    private fun server(id: String, url: String) =
        Server(id = id, name = id, hostname = id, localUrl = url, remoteUrl = "")

    private fun active(id: String, url: String) = ActiveServer(
        server = server(id, url),
        mode = ConnectionMode.Local,
        apiKey = "key-$id",
        keyState = ApiKeyResult.Present("key-$id"),
    )

    private fun content(url: String, value: String) =
        DomainState.Content(value = value, serverBaseUrl = url)

    /** Exactly the derivation in `UnraidRepository.activeBaseUrl`. */
    private fun activeBaseUrl(src: MutableStateFlow<ActiveServer?>) = src
        .map { a -> if (a == null || a.apiKey.isBlank()) null else a.server.localUrl }
        .distinctUntilChanged()

    /**
     * WHY: this is the core invariant. With server A producing Content,
     * then `activeWithKey` switching to server B, the consumer's stream for
     * B must NEVER surface A's Content — the first post-switch emission is
     * Loading (not A's stale data), and A's Content never reappears under B.
     */
    @Test
    fun switchingServers_neverSurfacesPreviousServersContent() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val activeServer = MutableStateFlow<ActiveServer?>(active("A", urlA))
        // Stand-in for the gated domainStream: its last value persists in
        // stateIn across the switch (the bug's exact mechanism — the poller
        // for B hasn't produced a value yet, so A's Content is still cached).
        val domain = MutableStateFlow<DomainState<String>>(content(urlA, "A-data"))

        val emissions = mutableListOf<DomainState<String>>()
        val job = scope.launch {
            combine(domain, activeBaseUrl(activeServer)) { state, url ->
                MainViewModel.resetIfForeignServer(state, url)
            }.collect { emissions += it }
        }

        scope.testScheduler.advanceUntilIdle()
        assertEquals(content(urlA, "A-data"), emissions.last())

        // User switches the active server to B. B's poll has NOT completed
        // yet — domain still holds A's stale Content.
        activeServer.value = active("B", urlB)
        scope.testScheduler.advanceUntilIdle()

        // Server A's Content must NEVER have been emitted while B is active.
        // i.e. once B became active, the consumer dropped to Loading.
        assertEquals(DomainState.Loading, emissions.last())
        assertFalse(
            "A's Content must never appear under B",
            emissions.drop(1).any {
                it is DomainState.Content<String> && it.serverBaseUrl == urlA &&
                    emissions.indexOf(it) > emissions.indexOf(DomainState.Loading)
            },
        )

        // B's first poll finally lands.
        domain.value = content(urlB, "B-data")
        scope.testScheduler.advanceUntilIdle()
        assertEquals(content(urlB, "B-data"), emissions.last())

        job.cancel()
    }

    /**
     * WHY (hard constraint, ADR-0017): an ordinary same-server gate toggle
     * (tab switch / background-resume) must NOT introduce a Loading flash.
     * The guard is keyed on server identity only, so Content whose
     * serverBaseUrl already matches the active server passes through
     * untouched — no new skeleton.
     */
    @Test
    fun sameServerContentPassesThroughWithoutLoadingFlash() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val activeServer = MutableStateFlow<ActiveServer?>(active("A", urlA))
        val domain = MutableStateFlow<DomainState<String>>(content(urlA, "A-1"))

        val emissions = mutableListOf<DomainState<String>>()
        val job = scope.launch {
            combine(domain, activeBaseUrl(activeServer)) { state, url ->
                MainViewModel.resetIfForeignServer(state, url)
            }.collect { emissions += it }
        }
        scope.testScheduler.advanceUntilIdle()

        // Same server, fresh poll value (what a tab re-entry / resume
        // produces) — must pass straight through, no Loading ever.
        domain.value = content(urlA, "A-2")
        scope.testScheduler.advanceUntilIdle()

        assertEquals(content(urlA, "A-2"), emissions.last())
        assertFalse(
            "no Loading flash for a same-server update",
            emissions.any { it == DomainState.Loading },
        )

        job.cancel()
    }

    /**
     * Rule 9 — pre-fix reproduction. WITHOUT the identity guard (raw gated
     * stream, which is what shipped before 0.1.31-beta8), switching to B
     * while the cache still holds A's Content surfaces A's data under B.
     * This asserts the bug exists absent the guard, so the tests above can
     * only pass because the guard is present and correct.
     */
    @Test
    fun withoutGuard_theBugReproduces() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val activeServer = MutableStateFlow<ActiveServer?>(active("A", urlA))
        val domain = MutableStateFlow<DomainState<String>>(content(urlA, "A-data"))

        val emissions = mutableListOf<DomainState<String>>()
        val job = scope.launch {
            // No resetIfForeignServer — the pre-fix pipeline.
            combine(domain, activeBaseUrl(activeServer)) { state, _ -> state }
                .collect { emissions += it }
        }
        scope.testScheduler.advanceUntilIdle()

        activeServer.value = active("B", urlB)
        scope.testScheduler.advanceUntilIdle()

        // Pre-fix: B is active but the consumer still sees A's Content.
        val leaked = emissions.last()
        assertTrue(
            "pre-fix pipeline must still leak A's Content under B",
            leaked is DomainState.Content<String> && leaked.serverBaseUrl == urlA,
        )
        assertFalse(leaked == DomainState.Loading)

        job.cancel()
    }
}
