package io.github.nofuturekid.nova.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.repository.ServerRepository.Companion.nextActiveAfterDelete
import io.github.nofuturekid.nova.data.repository.ServerRepository.Companion.resolveActiveServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Triage #20 — [ServerRepository.delete] of the ONLY / last configured
 * server must be safe and resolve to a well-defined "no server" state.
 *
 * The risk: `delete` resets the active server via
 * `setActiveServer(<remaining>.firstOrNull()?.id)`. When the deleted
 * server was the last one, the remaining list is empty so this is
 * `setActiveServer(null)`. The concern (triage #20) is whether that
 * leaves the active-server state undefined / NPEs downstream.
 *
 * Documented-safe intent being pinned here:
 *  - deleting the only server throws NO exception;
 *  - the active-server reset decision is the well-defined "no server"
 *    representation, which in this codebase is `null` (the persisted
 *    active id is cleared);
 *  - the derived active server resolves to `null`, which is exactly
 *    what `activeWithKey` keys on (`s?.let { … }` → `null`) and what
 *    [UnraidRepository.domainStream] maps to [DomainState.NoServer] —
 *    so downstream is the defined NoServer state, not a crash.
 *  - the sane multi→one case still picks a valid survivor as active
 *    (delete must never leave the app pointing at a deleted server).
 *
 * WHY this matters (Rule 9): removing your last server is a normal,
 * reachable user action (onboarding mistake, account teardown). If it
 * NPE'd or left a dangling active id, the dashboard would crash or keep
 * trying to poll a server that no longer exists instead of cleanly
 * showing the empty/onboarding state. This test encodes that the
 * last-delete path is *intentionally* the null/NoServer state, not an
 * accident of null-safety that a refactor could silently break.
 *
 * It drives the REAL production decision functions
 * ([ServerRepository.nextActiveAfterDelete] — the exact id `delete`
 * persists — and [ServerRepository.resolveActiveServer] — the exact
 * rule the production `activeServer` flow uses), via the same-module
 * `internal` seam convention already established here
 * (UnraidRepository.pollDomainForTest / UpdateRepository.parseVersion).
 * Single shared implementation → if the reset rule or the resolution
 * rule regresses, this test fails rather than pinning a stale copy.
 *
 * It fails if delete-of-last starts NPEing, or stops yielding the
 * well-defined no-server (null) active state, or if multi→one delete
 * stops promoting a surviving server to active.
 *
 * Collector style mirrors StaleCrossServerTest / TransientErrorToleranceTest
 * (launch into a list on a virtual scheduler); JUnit +
 * kotlinx-coroutines-test only, no new deps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteOnlyServerTest {

    private fun server(id: String) =
        Server(id = id, name = id, hostname = id, localUrl = "http://$id.local", remoteUrl = "")

    /**
     * Reproduces the real store-flow → `activeServer` → `activeWithKey`
     * shape: `combine(servers, activeServerId)` resolved by the
     * production [ServerRepository.resolveActiveServer], then the
     * production `s?.let { … }` no-server mapping. We assert the
     * resolved active *identity*; the key derivation is `null`-gated
     * exactly as production, so a `null` server here is precisely the
     * `DomainState.NoServer` path.
     */
    private fun activeWithKeyIdOf(
        servers: MutableStateFlow<List<Server>>,
        activeId: MutableStateFlow<String?>,
    ) = combine(servers, activeId) { list, id -> resolveActiveServer(list, id) }
        // production activeWithKey is `s?.let { ActiveServer(...) }` — a
        // null server is the "no server" branch; we key on identity.
        .map { s -> s?.id }

    /**
     * #20 core: start with exactly one server, configured + active;
     * delete it; assert no throw + well-defined no-server state.
     */
    @Test
    fun deletingTheOnlyServer_isSafe_andResolvesToNoServer() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val only = server("only")

        // Exactly one server, configured and active (the persisted
        // active id points at it) — the precondition triage #20 is about.
        val servers = MutableStateFlow(listOf(only))
        val activeId = MutableStateFlow<String?>(only.id)

        val resolvedActiveIds = mutableListOf<String?>()
        val job = scope.launch {
            activeWithKeyIdOf(servers, activeId).collect { resolvedActiveIds += it }
        }
        scope.testScheduler.advanceUntilIdle()
        // Sanity: with one active server, it resolves as active.
        // WHY: confirms the precondition is real before we delete it.
        assertEquals(only.id, resolvedActiveIds.last())

        // ── Delete the ONLY server. This is the exact production
        // sequence inside ServerRepository.delete: filter it out of the
        // list, then re-derive the active id via the REAL decision fn.
        // NO exception must be thrown by the reset decision (the #20
        // risk: empty list → firstOrNull()?.id). If nextActiveAfterDelete
        // ever NPE'd on an empty remaining list this line throws and the
        // test fails — which is the bug, not the test's fault.
        val remaining = servers.value.filter { it.id != only.id }
        val nextActive: String? = nextActiveAfterDelete(remaining)
        servers.value = remaining
        activeId.value = nextActive
        scope.testScheduler.advanceUntilIdle()

        // The well-defined "no server" representation in this codebase is
        // null: the persisted active id is cleared. WHY: this is the
        // documented contract — last-delete is *intentionally* the
        // null/NoServer state; if delete started persisting a dangling
        // id (e.g. the just-deleted one) this fails.
        assertNull(
            "deleting the only server must clear the active id (no server)",
            nextActive,
        )

        // The derived active server resolves to null, which is exactly
        // the no-server branch of activeWithKey (s?.let → null) and what
        // domainStream maps to DomainState.NoServer. WHY: proves the
        // downstream is the *defined* NoServer state, not undefined /
        // a crash. If resolveActiveServer stopped yielding null on an
        // empty list this fails.
        assertNull(
            "no servers → no active server (→ DomainState.NoServer)",
            resolvedActiveIds.last(),
        )

        job.cancel()
    }

    /**
     * #20 sane multi→one case: deleting one of two servers must promote
     * a surviving server to active — delete still picks a valid active
     * when one exists, so this guards that the null-on-empty behaviour
     * above is specific to "no servers left", not a blanket reset.
     */
    @Test
    fun deletingOneOfTwo_promotesASurvivorToActive() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val a = server("a")
        val b = server("b")

        val servers = MutableStateFlow(listOf(a, b))
        val activeId = MutableStateFlow<String?>(a.id) // 'a' is active

        val resolvedActiveIds = mutableListOf<String?>()
        val job = scope.launch {
            activeWithKeyIdOf(servers, activeId).collect { resolvedActiveIds += it }
        }
        scope.testScheduler.advanceUntilIdle()
        assertEquals(a.id, resolvedActiveIds.last())

        // Delete the active server 'a' (one of two) — real delete logic.
        val remaining = servers.value.filter { it.id != a.id }
        val nextActive: String? = nextActiveAfterDelete(remaining)
        servers.value = remaining
        activeId.value = nextActive
        scope.testScheduler.advanceUntilIdle()

        // A survivor ('b') must become active — NOT null. WHY: with
        // servers still configured the app must never fall back to the
        // no-server state; delete promotes the first survivor. Fails if
        // delete cleared the active id while servers remain.
        assertEquals(
            "a surviving server must become active when one remains",
            b.id,
            nextActive,
        )
        assertEquals(b.id, resolvedActiveIds.last())

        job.cancel()
    }
}
