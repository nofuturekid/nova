package net.unraidcontrol.app.data.repository

import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloNetworkException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triage #19 — error / network-failure handling of notification actions
 * (archive / unread / delete / archive-all / delete-all-archived).
 *
 * What this pins: the *actual* error contract of the production
 * notification-action path. The sheet + bell are documented as
 * action→refetch with no optimistic local mutation — the repository
 * runs the mutation, a server `recalculateOverview`, then nudges the
 * notifications stream so sheet+bell converge on server truth
 * (UnraidRepository / NotificationsSheet KDoc). The triage question:
 * when the mutation network-fails, what actually happens?
 *
 * Observed contract (read from [UnraidRepository.runNotificationActionFlow],
 * the single shared impl the production `runNotificationAction` delegates
 * to — same-module `internal` seam convention, cf. `pollDomainForTest` /
 * `UpdateRepository.parseVersion`; no parallel harness, no stale copy):
 *
 *  1. No active client (no server / blank key) → silent no-op, NO nudge.
 *  2. The mutation itself is NOT `runCatching`-guarded: a network
 *     failure PROPAGATES out of the action. The post-mutation
 *     `recalculateOverview` and the stream nudge are therefore SKIPPED —
 *     a failed action does NOT trigger the convergence refetch.
 *  3. Only the `recalculateOverview` is `runCatching`-guarded: if the
 *     mutation succeeded but the overview recalc fails, that is
 *     swallowed and the nudge STILL fires (next poll reconciles counts).
 *
 * WHY this matters (Rule 9): tapping Archive / Delete on a notification
 * is a normal, reachable action that runs over a flaky LAN/remote link.
 * The contract above is deliberate for the *recalc* (the poll will
 * reconcile a stale overview), but a failed *mutation* propagates with
 * the nudge skipped — i.e. the action→refetch convergence does NOT run
 * on failure, and there is no in-repository user-facing signal. This
 * test pins each branch so a refactor that (a) starts swallowing the
 * mutation failure silently, or (b) starts guarding/altering the recalc
 * vs nudge ordering, fails loudly instead of silently changing the UX.
 *
 * It drives the REAL production control flow via the `internal`
 * [UnraidRepository.Companion.runNotificationActionFlow] seam — the exact
 * function the production action delegates to — with scripted lambdas.
 * JUnit + kotlinx-coroutines-test only, no new deps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionErrorTest {

    /** Stand-in "client" — identity only; the seam is client-type generic. */
    private object FakeClient

    /**
     * #19 core: a notification mutation that network-fails. The failure
     * must PROPAGATE (not be swallowed inside the action), and because it
     * propagates the recalc + nudge must NOT run.
     *
     * WHY: pins that the mutation is intentionally un-guarded. If a
     * refactor wrapped `mutate` in runCatching, the throw would vanish
     * and the recalc/nudge would run on a failed action — silently
     * changing behaviour. Fails if either the throw stops propagating or
     * the post-mutation steps start running after a failed mutation.
     */
    @Test
    fun failingMutation_propagates_andSkipsRecalcAndNudge() = runTest {
        var recalcRan = false
        var nudged = false

        // Drive the real seam in-coroutine (runTest body IS a coroutine);
        // capture the escaping exception rather than nesting runBlocking.
        var thrown: Throwable? = null
        try {
            UnraidRepository.runNotificationActionFlow(
                resolveClient = { FakeClient },
                mutate = { throw ApolloNetworkException("boom: socket closed") },
                recalculate = { recalcRan = true },
                nudge = { nudged = true },
            )
        } catch (e: ApolloException) {
            thrown = e
        }

        // The exact failure mode: the network exception escapes the
        // action unchanged (no swallow, no remap). WHY: documents that
        // the only signal a failed notification action gives is the
        // propagated exception — there is no in-repository fallback. A
        // null here means the action swallowed it (contract regressed).
        assertTrue("mutation failure must propagate, not be swallowed", thrown is ApolloException)
        assertEquals("boom: socket closed", thrown?.message)

        // The recalc must NOT have run — the throw short-circuited before
        // it. WHY: a failed mutation means there is nothing new to
        // reconcile a server overview against; pins the ordering.
        assertFalse("recalc must not run after a failed mutation", recalcRan)

        // The nudge must NOT have fired. WHY: this is the load-bearing
        // UX fact (triage #19) — the action→refetch convergence does NOT
        // run when the mutation fails, so the sheet/bell do not refresh
        // off the back of a failed tap. Fails if the nudge moves before
        // the mutation or the mutation gets guarded.
        assertFalse("stream nudge must not fire on a failed mutation", nudged)
    }

    /**
     * #19: no active server / unusable key → the action is a silent
     * no-op. No mutation, no recalc, no nudge, no throw.
     *
     * WHY: pins that an action with no server is intentionally inert
     * (the UI is in a no-server state anyway) — it must never NPE or
     * spuriously nudge the stream. Fails if the early-return guard is
     * removed.
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
     * #19: the mutation SUCCEEDS but the follow-up
     * `recalculateOverview` network-fails. The recalc failure is
     * `runCatching`-swallowed and the nudge STILL fires.
     *
     * WHY: this is the *intentional* resilience branch — a stale server
     * overview is acceptable because the very next poll (triggered by
     * the nudge) reconciles the counts. Pins that the recalc is the
     * guarded step (not the mutation) and that nudge survives a recalc
     * failure. Fails if the recalc loses its guard (would propagate) or
     * if the nudge gets gated on recalc success.
     */
    @Test
    fun failingRecalc_isSwallowed_andNudgeStillFires() = runTest {
        var mutateRan = false
        var nudged = false

        // Must NOT throw — the recalc failure is swallowed.
        UnraidRepository.runNotificationActionFlow(
            resolveClient = { FakeClient },
            mutate = { mutateRan = true },
            recalculate = { throw ApolloNetworkException("recalc down") },
            nudge = { nudged = true },
        )

        // The mutation ran (it is the successful step here). WHY:
        // anchors that we are exercising the post-success branch.
        assertTrue("mutation should have run", mutateRan)

        // The nudge fired despite the recalc failing. WHY: the
        // action→refetch convergence MUST still kick the poll so the
        // sheet/bell reconcile even when the server overview recalc
        // failed — that is the whole point of the action→refetch design.
        assertTrue("nudge must fire even when recalc fails", nudged)
    }
}
