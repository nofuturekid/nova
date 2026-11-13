package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.NotifImportance
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.UnraidNotification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs whole checks the way the background worker will: probe, evaluate, store, over several
 * rounds and servers, on the in-memory fakes.
 *
 * WHY: the rules are only right if the remembered state really carries from one check to the
 * next and stays per server. These tests would fail if a server's state leaked into another's,
 * if a failed probe overwrote the memory, or if switching alerts off did not make the next
 * start a silent first check.
 */
class AlertCheckCycleTest {

    private val tower = server("srv-tower", "Tower")
    private val backup = server("srv-backup", "Backup")
    private val probe = FakeServerProbe()
    private val store = InMemoryAlertStateStore()

    /** One check of [server], as the worker will do it. Returns the keys that would be posted. */
    private fun check(server: Server, level: AlertLevel = AlertLevel.AlertsAndWarnings): List<String> = runBlocking {
        val result = probe.probe(server, pin = null)
        val evaluation = evaluateAlerts(
            ServerRef(server.id, server.name), store.get(server.id),
            result.loadedNotifications, result.loadedArray, level,
        )
        store.put(server.id, evaluation.nextState)
        evaluation.toPost.map { it.key }
    }

    @Test fun a_notification_is_posted_by_exactly_one_check() {
        probe.answer(tower.id, ok(listOf(alert("n1")), ArrayState.Started))
        assertEquals("first check seeds", emptyList<String>(), check(tower))

        probe.answer(tower.id, ok(listOf(alert("n1"), alert("n2")), ArrayState.Started))
        assertEquals(listOf("n2"), check(tower))
        assertEquals(emptyList<String>(), check(tower))
        assertEquals(emptyList<String>(), check(tower))
    }

    @Test fun state_is_independent_per_server() {
        // WHY: the same notification id on two servers is two events; one server's baseline
        // must not silence, and one server's memory must not suppress, the other.
        probe.answer(tower.id, ok(emptyList(), ArrayState.Started))
        check(tower)
        // Backup is added later and has not been baselined yet.
        probe.answer(tower.id, ok(listOf(alert("n1")), ArrayState.Started))
        probe.answer(backup.id, ok(listOf(alert("n1")), ArrayState.Stopped))

        assertEquals("Backup's first check is silent although Tower alerts", emptyList<String>(), check(backup))
        assertEquals(listOf("n1"), check(tower))
        assertEquals(emptyList<String>(), check(tower))
        assertEquals(setOf(tower.id, backup.id), store.serverIds)

        probe.answer(backup.id, ok(listOf(alert("n1"), alert("n2")), ArrayState.Stopped))
        assertEquals(listOf("n2"), check(backup))
    }

    @Test fun an_unreachable_check_keeps_the_memory_so_nothing_repeats_afterwards() {
        probe.answer(tower.id, ok(listOf(alert("n1")), ArrayState.Stopped))
        check(tower)
        probe.answer(tower.id, ProbeResult.Unreachable)
        assertEquals(emptyList<String>(), check(tower))
        probe.answer(tower.id, ok(listOf(alert("n1")), ArrayState.Stopped))
        assertEquals("neither the old alert nor the old array condition returns", emptyList<String>(), check(tower))
    }

    @Test fun removing_a_server_forgets_it_and_a_server_added_again_starts_silent() {
        probe.answer(tower.id, ok(listOf(alert("n1")), ArrayState.Started))
        check(tower)
        runBlocking { store.remove(tower.id) }
        assertEquals(emptySet<String>(), store.serverIds)

        probe.answer(tower.id, ok(listOf(alert("n1"), alert("n2")), ArrayState.Started))
        assertEquals("a re-added server is baselined again, not flooded", emptyList<String>(), check(tower))
    }

    @Test fun switching_alerts_off_clears_everything_so_the_next_start_is_a_silent_first_check() {
        probe.answer(tower.id, ok(listOf(alert("n1")), ArrayState.Started))
        probe.answer(backup.id, ok(listOf(alert("n9")), ArrayState.Started))
        check(tower); check(backup)
        runBlocking { store.clear() }
        assertEquals(emptySet<String>(), store.serverIds)

        probe.answer(tower.id, ok(listOf(alert("n1"), alert("n2")), ArrayState.Started))
        assertEquals(emptyList<String>(), check(tower))
    }

    @Test fun the_stored_state_stays_bounded_over_many_checks() {
        // WHY: a server that keeps raising notifications for years must not make the stored
        // state grow without end.
        probe.answer(tower.id, ok(emptyList(), ArrayState.Started))
        check(tower)
        var round = 0
        repeat(40) {
            val batch = (1..20).map { alert("n${round++}") }
            probe.answer(tower.id, ok(batch, ArrayState.Started))
            check(tower)
        }
        val seen = runBlocking { store.get(tower.id) }.seenNotificationIds
        assertEquals(MAX_SEEN_NOTIFICATION_IDS, seen.size)
        assertTrue("the newest id is remembered", "n${round - 1}" in seen)
    }

    private fun ok(items: List<UnraidNotification>, state: ArrayState) = ProbeResult.Ok(
        Notifications(
            unreadWarning = 0, unreadAlert = items.size, unreadTotal = items.size, items = items,
        ),
        ArrayInfo(state, totalTb = 8.0, usedTb = 2.0, parity = null, disks = emptyList()),
    )

    private fun alert(id: String) = UnraidNotification(
        id = id, title = "Title", subject = "Alert $id", description = "Details", importance = NotifImportance.Alert,
        type = null, link = null, timestamp = null, formattedTimestamp = null,
    )

    private fun server(id: String, name: String) = Server(
        id = id, name = name, hostname = "$id.example", localUrl = "https://$id.example", remoteUrl = "",
    )
}
