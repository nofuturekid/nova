package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloNetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import io.github.nofuturekid.nova.ui.screens.main.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triage #19 — error / network-failure handling of notification actions
 * (archive / unread / delete / archive-all / delete-all-archived), and
 * the SAME unguarded class for docker / VM / array / parity actions.
 *
 * CONTRACT FLIP (ADR-0037). Before 0.1.32-beta2 these tests pinned the
 * OLD shape: a failing notification mutation PROPAGATED out of the
 * action with the nudge/refetch skipped and NO user-facing signal —
 * which, launched as a bare `viewModelScope.launch { unraid.x() }` with
 * no try/catch and no global handler, could CRASH the app on a routine
 * tap. That contract is the bug. These tests now pin the FIXED intent:
 *
 *  1. A failing user action does NOT propagate / crash. The exception is
 *     caught at the ViewModel resilience boundary
 *     ([MainViewModel.runResilientAction], the exact pure seam the
 *     production `launchAction` delegates to) and exactly one transient
 *     message is surfaced for the snackbar.
 *  2. The repository seam ([UnraidRepository.runNotificationActionFlow])
 *     is unchanged: it still resolves the client, runs the mutation,
 *     `runCatching`-guards the overview recalc, then nudges. The
 *     resilience now lives one layer up (the launcher), not in the repo.
 *  3. CancellationException is NOT swallowed by the resilient launcher —
 *     structured concurrency (scope clear, parent cancel) must keep
 *     working, otherwise a backgrounded/cleared ViewModel would leak.
 *  4. The action→server-truth convergence is intact: when the mutation
 *     SUCCEEDS the recalc + nudge still fire (refetch reconciles), and
 *     `onFinally` bookkeeping always runs (updating-pill never stranded).
 *
 * WHY this matters (Rule 9): tapping Archive / Delete (or a container /
 * VM / array / parity start-stop) is a normal, reachable action over a
 * flaky LAN/remote link. The crash path is the defect. These tests fail
 * loudly if the crash path regresses — i.e. if the launcher stops
 * catching the throwable, stops emitting the signal, starts swallowing
 * CancellationException, or skips the post-action convergence on success.
 *
 * Both production seams are driven directly via their same-module
 * `internal` test entrypoints (cf. `pollDomainForTest`,
 * `UpdateRepository.parseVersion`) with scripted lambdas — no parallel
 * harness, no stale copy. JUnit + kotlinx-coroutines-test only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionErrorTest {

    /** Stand-in "client" — identity only; the seam is client-type generic. */
    private object FakeClient

    /**
     * #19 CORE (contract flipped): a notification action whose mutation
     * network-fails, launched the way production launches it — through the
     * resilient launcher wrapping the real repository flow. The failure
     * must NOT propagate (no crash) and a transient message must surface.
     *
     * WHY: this is the load-bearing UX fact. Before the fix this exact
     * scenario threw out of `viewModelScope.launch` and crashed the app.
     * Fails if the launcher stops catching the throwable (crash regresses)
     * or stops emitting the user signal (silent failure regresses).
     */
    @Test
    fun failingNotificationAction_doesNotCrash_andSurfacesTransientMessage() = runTest {
        var recalcRan = false
        var nudged = false
        val messages = mutableListOf<String>()
        var escaped: Throwable? = null

        try {
            MainViewModel.runResilientAction(
                emit = { messages += it },
            ) {
                // The exact production composition: the resilient launcher
                // wraps the real repository notification-action flow.
                UnraidRepository.runNotificationActionFlow(
                    resolveClient = { FakeClient },
                    mutate = { throw ApolloNetworkException("boom: socket closed") },
                    recalculate = { recalcRan = true },
                    nudge = { nudged = true },
                )
            }
        } catch (e: Throwable) {
            escaped = e
        }

        // Nothing escapes the launcher → viewModelScope is never torn
        // down → the app does NOT crash. THE fix for triage #19.
        assertNull("a failed action must not propagate / crash", escaped)

        // The user gets exactly one transient signal (snackbar). Before
        // the fix there was none (and a crash instead).
        assertEquals(listOf(MainViewModel.ACTION_FAILED_MESSAGE), messages)

        // The repository contract is unchanged: a failed *mutation* still
        // short-circuits before the recalc + nudge (there is nothing new
        // to reconcile against). The convergence on failure comes from the
        // domain poll loop's transient-error tolerance, not a phantom
        // nudge. Pins that we did not "fix" this by silently reordering
        // the repo seam.
        assertFalse("recalc must not run after a failed mutation", recalcRan)
        assertFalse("nudge must not fire on a failed mutation", nudged)
    }

    /**
     * #19: CancellationException must STILL cancel — the resilient
     * launcher catches `Throwable` but must re-throw cancellation so
     * structured concurrency keeps working (a cleared/backgrounded
     * ViewModel's coroutines must actually stop).
     *
     * WHY: a `catch (Throwable)` that swallows CancellationException is a
     * classic coroutines footgun — it would leak work after the scope is
     * cancelled. Fails if the launcher starts eating cancellation.
     */
    @Test
    fun cancellation_isNotSwallowed_byResilientLauncher() = runTest {
        val messages = mutableListOf<String>()
        var cancelled = false
        var finallyRan = false

        try {
            MainViewModel.runResilientAction(
                emit = { messages += it },
                onFinally = { finallyRan = true },
            ) {
                throw CancellationException("scope cleared")
            }
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue("CancellationException must propagate (not be swallowed)", cancelled)
        assertTrue("onFinally must still run on cancellation", finallyRan)
        assertTrue("no user message on a normal cancellation", messages.isEmpty())
    }

    /**
     * #19: the repository seam itself is unchanged — no active server /
     * unusable key is still a silent no-op (no mutation, no nudge, no
     * throw). Pins that the early-return guard survived the refactor.
     */
    @Test
    fun noActiveClient_isSilentNoOp() = runTest {
        var mutateRan = false
        var nudged = false

        UnraidRepository.runNotificationActionFlow<FakeClient>(
            resolveClient = { null },
            mutate = { mutateRan = true },
            recalculate = { },
            nudge = { nudged = true },
        )

        assertFalse("no client → mutation must not run", mutateRan)
        assertFalse("no client → no nudge", nudged)
    }

    /**
     * #19: the action→server-truth convergence is intact. When the
     * mutation SUCCEEDS but the follow-up `recalculateOverview`
     * network-fails, the recalc failure is `runCatching`-swallowed, the
     * nudge STILL fires (next poll reconciles counts), the launcher
     * surfaces NO error, and `onFinally` bookkeeping still runs.
     *
     * WHY: pins that "resilient" did not mean "swallow everything and
     * stop refetching" — the happy/partial path still converges and the
     * user is not spuriously warned on a success.
     */
    @Test
    fun successfulAction_stillConverges_andDoesNotWarnUser() = runTest {
        var mutateRan = false
        var nudged = false
        var finallyRan = false
        val messages = mutableListOf<String>()

        MainViewModel.runResilientAction(
            emit = { messages += it },
            onFinally = { finallyRan = true },
        ) {
            UnraidRepository.runNotificationActionFlow(
                resolveClient = { FakeClient },
                mutate = { mutateRan = true },
                recalculate = { throw ApolloNetworkException("recalc down") },
                nudge = { nudged = true },
            )
        }

        assertTrue("mutation should have run", mutateRan)
        assertTrue("nudge must fire so the poll reconciles (action→refetch)", nudged)
        assertTrue("onFinally must run on the success path", finallyRan)
        assertTrue("a successful action must NOT warn the user", messages.isEmpty())
    }

    /**
     * The same crash-class for non-notification actions: a docker / VM /
     * array / parity action is just `launchAction { unraid.x() }`, i.e.
     * the same [MainViewModel.runResilientAction] wrapping a throwing
     * suspend call. A network failure there must also not propagate and
     * must surface the transient message.
     *
     * WHY: triage #19 found it on notifications; this asserts the shared
     * seam fixes the whole class, so a future un-routed launcher (a bare
     * `viewModelScope.launch { unraid.x() }`) is a visible regression.
     */
    @Test
    fun failingServerAction_sameClass_isAlsoHandled() = runTest {
        val messages = mutableListOf<String>()
        var escaped: Throwable? = null

        try {
            MainViewModel.runResilientAction(emit = { messages += it }) {
                // Stand-in for unraid.stopContainer(id) / startVm(id) / … —
                // a throwing suspend repository action.
                throw ApolloNetworkException("container stop: connection reset")
            }
        } catch (e: ApolloException) {
            escaped = e
        }

        assertNull("a failed container/VM/array/parity action must not crash", escaped)
        assertEquals(listOf(MainViewModel.ACTION_FAILED_MESSAGE), messages)
    }
}
