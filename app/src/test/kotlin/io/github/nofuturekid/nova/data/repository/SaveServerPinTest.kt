package io.github.nofuturekid.nova.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.data.alerts.AlertNotifier
import io.github.nofuturekid.nova.data.alerts.AlertScheduler
import io.github.nofuturekid.nova.data.alerts.SettingsAlertStateStore
import io.github.nofuturekid.nova.data.local.ApiKeyStore
import io.github.nofuturekid.nova.data.local.SettingsStore
import io.github.nofuturekid.nova.data.model.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WHY: the certificate the user trusted while pressing Test is the pin that
 * must be in place at the moment the new server becomes the active one. If the
 * server is stored first and the pin a moment later, the polling streams see a
 * "trust self-signed" server WITHOUT a pin, fail with an untrusted-certificate
 * error and raise the "Trust this certificate?" prompt on the main screen: the
 * user is asked twice for the same fingerprint (issue #292).
 *
 * Runs the real [ServerRepository] on the real DataStore files (Robolectric)
 * and records every value `activeWithKey` emits, because that flow is exactly
 * what the polling streams are built from. The invariant is checked over ALL
 * emissions, so a transient state between two writes cannot slip through.
 * A blank API key keeps the Android Keystore out of the test; the key is
 * written before the server list and does not take part in the ordering.
 *
 * The race window is a few milliseconds wide, so the flow is repeated; with
 * the old two-step write at least one round exposes it, with the single write
 * no round can.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SaveServerPinTest {
    private lateinit var repo: ServerRepository
    private val seen = CopyOnWriteArrayList<ActiveServer?>()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)
        repo = ServerRepository(
            store, ApiKeyStore(ctx),
            AlertScheduler(ctx, store, SettingsAlertStateStore(store), AlertNotifier(ctx)),
        )
        // The DataStore files outlive a test inside one JVM: start from "no server".
        store.servers.first().forEach { repo.delete(it.id) }
        seen.clear()
    }

    private fun server(id: String, trust: Boolean) = Server(
        id = id, name = "Tower", hostname = "tower.example",
        localUrl = "https://tower.example", remoteUrl = "", trustSelfSignedLocal = trust,
    )

    private fun pinFor(i: Int) = "AA:BB:%02X".format(i)

    /** Collects `activeWithKey` on a real dispatcher while [body] drives the repository. */
    private fun recording(body: suspend () -> Unit) = runBlocking {
        val job = CoroutineScope(Dispatchers.Default).launch { repo.activeWithKey.collect { seen.add(it) } }
        try {
            waitFor { seen.isNotEmpty() }
            body()
        } finally {
            job.cancel()
        }
    }

    private suspend fun waitFor(what: () -> Boolean) = withTimeout(10_000) { while (!what()) delay(5) }

    private fun assertNeverActiveWithoutItsPin() {
        val bad = seen.filterNotNull().filter { it.server.trustSelfSignedLocal && it.localCertPin == null }
        assertTrue(
            "a self-signed-trusting server became active without its pin ${bad.size} time(s); " +
                "the streams would raise a second certificate prompt",
            bad.isEmpty(),
        )
    }

    // WHY: adding a new server. The pin accepted in the sheet is saved with the entry.
    @Test fun newServer_isNeverActiveWithoutThePinAcceptedInTheSheet() = recording {
        repeat(25) { i ->
            val id = "new-$i"
            repo.upsert(server(id, trust = true), apiKey = "", localCertPin = pinFor(i))
            waitFor { seen.lastOrNull()?.let { it.server.id == id && it.localCertPin == pinFor(i) } == true }
            repo.delete(id)
            waitFor { seen.lastOrNull() == null }
        }
        assertNeverActiveWithoutItsPin()
    }

    // WHY: editing an existing server (trust switched on, certificate accepted at Test).
    @Test fun editedServer_isNeverActiveWithTrustButWithoutThePin() = recording {
        repeat(25) { i ->
            repo.upsert(server("edit", trust = false), apiKey = "")
            waitFor { seen.lastOrNull()?.let { it.server.id == "edit" && !it.server.trustSelfSignedLocal } == true }
            repo.upsert(server("edit", trust = true), apiKey = "", localCertPin = pinFor(i))
            waitFor { seen.lastOrNull()?.localCertPin == pinFor(i) }
        }
        assertNeverActiveWithoutItsPin()
    }

    // WHY (security): saving without a pin must not invent one. The certificate stays
    // untrusted, so the main screen has to ask - exactly once, for the real fingerprint.
    @Test fun trustOnWithoutAcceptedPin_storesNoPin() = runBlocking {
        repo.upsert(server("nopin", trust = true), apiKey = "")
        assertNull(repo.pinFor("nopin"))
    }

    // WHY (security): switching trust off must also drop the pin, even if the sheet still
    // carries a fingerprint, so a later opt-in starts with a fresh prompt.
    @Test fun trustOff_dropsThePin() = runBlocking {
        repo.upsert(server("off", trust = true), apiKey = "", localCertPin = "AA:01")
        assertEquals("AA:01", repo.pinFor("off"))
        repo.upsert(server("off", trust = false), apiKey = "", localCertPin = "AA:02")
        assertNull(repo.pinFor("off"))
    }

    // WHY: a save that carries no new pin (host untouched, nothing re-tested) must keep the
    // pin of an existing server instead of silently wiping it.
    @Test fun trustOnWithoutNewPin_keepsTheStoredPin() = runBlocking {
        repo.upsert(server("keep", trust = true), apiKey = "", localCertPin = "AA:01")
        repo.upsert(server("keep", trust = true).copy(name = "Renamed"), apiKey = "")
        assertEquals("AA:01", repo.pinFor("keep"))
    }
}
