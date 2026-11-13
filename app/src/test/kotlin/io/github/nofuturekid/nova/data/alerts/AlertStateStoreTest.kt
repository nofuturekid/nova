package io.github.nofuturekid.nova.data.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.data.local.ApiKeyStore
import io.github.nofuturekid.nova.data.local.SettingsStore
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.repository.ServerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WHY: the memory of what was already reported is worthless if it is lost or shared.
 * Lost at process death, every check after the system killed the app would be a silent
 * "first check" and real alerts would vanish, or (if it fell back to "nothing seen") the
 * whole backlog would be re-posted. Shared between servers, one server's baseline would
 * hide another's alerts. Kept after the server is deleted, it would sit on the phone for
 * good.
 *
 * Runs the real [SettingsAlertStateStore] on the real DataStore file (Robolectric). The
 * DataStore file outlives a test inside one JVM, so every test starts by clearing it. A new
 * store object over the same file stands for a restarted process: nothing is kept in memory.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AlertStateStoreTest {
    @get:Rule val stallWatch = StallWatch()

    private lateinit var ctx: Context

    @Before fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        SettingsAlertStateStore(SettingsStore(ctx)).clear()
    }

    private fun freshStore() = SettingsAlertStateStore(SettingsStore(ctx))

    private val busy = ServerAlertState(true, listOf("n1", "n2"), true, setOf("array-stopped"))

    @Test fun a_server_without_state_is_not_baselined() = runBlocking {
        assertEquals(ServerAlertState(), freshStore().get("unknown"))
    }

    @Test fun state_survives_a_restart() = runBlocking {
        freshStore().put("srv-1", busy)
        assertEquals(busy, freshStore().get("srv-1"))
    }

    @Test fun servers_do_not_share_state() = runBlocking {
        val store = freshStore()
        val other = ServerAlertState(true, listOf("x"), false, emptySet())
        store.put("srv-1", busy)
        store.put("srv-2", other)
        assertEquals(busy, freshStore().get("srv-1"))
        assertEquals(other, freshStore().get("srv-2"))
    }

    @Test fun put_replaces_a_servers_state_instead_of_adding_to_it() = runBlocking {
        val store = freshStore()
        store.put("srv-1", busy)
        store.put("srv-1", ServerAlertState(true, listOf("n3"), true, emptySet()))
        assertEquals(listOf("n3"), freshStore().get("srv-1").seenNotificationIds)
    }

    @Test fun remove_forgets_only_that_server() = runBlocking {
        val store = freshStore()
        store.put("srv-1", busy)
        store.put("srv-2", busy)
        store.remove("srv-1")
        assertEquals(ServerAlertState(), freshStore().get("srv-1"))
        assertEquals(busy, freshStore().get("srv-2"))
    }

    @Test fun clear_forgets_every_server() = runBlocking {
        val store = freshStore()
        store.put("srv-1", busy)
        store.put("srv-2", busy)
        store.clear()
        assertEquals(ServerAlertState(), freshStore().get("srv-1"))
        assertEquals(ServerAlertState(), freshStore().get("srv-2"))
    }

    @Test fun deleting_a_server_entry_removes_its_alert_state() = runBlocking {
        // WHY: a deleted server must not leave its list of notification ids on the phone, and a
        // server added again must be baselined afresh instead of inheriting old memory.
        val settings = SettingsStore(ctx)
        val repo = ServerRepository(
            settings, ApiKeyStore(ctx),
            AlertScheduler(ctx, settings, SettingsAlertStateStore(settings), AlertNotifier(ctx)),
        )
        settings.servers.first().forEach { repo.delete(it.id) }

        // A blank key keeps the Android Keystore out of the test.
        val kept = repo.upsert(server("Tower"), apiKey = "")
        val deleted = repo.upsert(server("Backup"), apiKey = "")
        val store = SettingsAlertStateStore(settings)
        store.put(kept.id, busy)
        store.put(deleted.id, busy)

        repo.delete(deleted.id)

        assertEquals(ServerAlertState(), freshStore().get(deleted.id))
        assertEquals(busy, freshStore().get(kept.id))
        repo.delete(kept.id)
    }

    private fun server(name: String) = Server(
        id = "", name = name, hostname = "${name.lowercase()}.example",
        localUrl = "https://${name.lowercase()}.example", remoteUrl = "",
    )
}
