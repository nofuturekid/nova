package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.model.Access
import io.github.nofuturekid.nova.data.model.ApiVersion
import io.github.nofuturekid.nova.data.model.CapabilityTarget
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Detection
import io.github.nofuturekid.nova.data.model.KeyAccess
import io.github.nofuturekid.nova.data.model.KeyAction
import io.github.nofuturekid.nova.data.model.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything here runs at one point of virtual time (there are no delays to wait for), so
 * `runCurrent()` settles it. `advanceUntilIdle()` would skip the repository's work: it runs in
 * `backgroundScope`, which that call deliberately ignores.
 *
 * ADR-0047. These tests pin WHEN detection runs (target change, refresh, start; never on a timer)
 * and that a failure or a stale answer can never change what the user sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityRepositoryTest {

    private fun active(id: String = "s1", name: String = "Tower", url: String = "http://tower", key: String = "key-1") =
        ActiveServer(
            server = Server(id = id, name = name, hostname = name, localUrl = url, remoteUrl = ""),
            mode = ConnectionMode.Local,
            apiKey = key,
            keyState = if (key.isBlank()) ApiKeyResult.Absent else ApiKeyResult.Present(key),
        )

    private val targetOf: (ActiveServer?) -> CapabilityTarget? = { a ->
        a?.takeIf { it.apiKey.isNotBlank() }?.let { CapabilityTarget(it.server.id, it.server.localUrl, it.apiKey) }
    }

    private val viewer = ProbeOutcome(
        Detection.Done, "7.4.0", "4.37.11+8e587fe2", ApiVersion(4, 37, 11),
        access = KeyAccess.Known(setOf("VIEWER"), emptySet()),
    )
    private val admin = viewer.copy(access = KeyAccess.Known(setOf("ADMIN"), emptySet()))
    private val failed = ProbeOutcome(Detection.Failed)

    /** Probe that answers from a queue (the last answer repeats) and counts its calls. */
    private class Script(vararg answers: suspend () -> ProbeOutcome) {
        private val queue = answers.toList()
        var calls = 0
        val seen = mutableListOf<ActiveServer>()
        suspend fun run(a: ActiveServer): ProbeOutcome {
            seen += a
            val answer = queue[minOf(calls, queue.size - 1)]
            calls++
            return answer()
        }
    }

    private fun answer(o: ProbeOutcome): suspend () -> ProbeOutcome = { o }

    private fun TestScope.repo(source: MutableStateFlow<ActiveServer?>, script: Script) =
        CapabilityRepository(backgroundScope, source, targetOf, script::run)

    // WHY: before any detection the answer is unknown, which shows the controls as today.
    @Test fun startsUnknown_andWithoutKeyNeverProbes() = runTest {
        val script = Script(answer(viewer))
        val r = repo(MutableStateFlow(active(key = "")), script)
        runCurrent()
        assertEquals(Detection.Idle, r.capabilities.value.detection)
        assertNull(r.capabilities.value.target)
        assertEquals(0, script.calls)
    }

    @Test fun detectsOnceForTheActiveServer() = runTest {
        val script = Script(answer(viewer))
        val r = repo(MutableStateFlow(active()), script)
        runCurrent()
        assertEquals(1, script.calls)
        assertEquals(Detection.Done, r.capabilities.value.detection)
        assertEquals(Access.Denied, r.capabilities.value.access(KeyAction.ControlDocker))
    }

    // WHY: one server's "denied" must never gate another server while the second one is still unknown.
    @Test fun newTargetResetsToUnknownFirst() = runTest {
        val gate = CompletableDeferred<ProbeOutcome>()
        val source = MutableStateFlow<ActiveServer?>(active("a", key = "ka"))
        val r = repo(source, Script(answer(viewer), { gate.await() }))
        runCurrent()
        assertEquals(Access.Denied, r.capabilities.value.access(KeyAction.ControlDocker))

        source.value = active("b", url = "http://other", key = "kb")
        runCurrent()
        val now = r.capabilities.value
        assertEquals(Detection.Detecting, now.detection)
        assertEquals("b", now.target?.serverId)
        assertTrue(KeyAction.entries.all { now.access(it) == Access.Unknown })

        gate.complete(admin)
        runCurrent()
        assertEquals(Access.Allowed, r.capabilities.value.access(KeyAction.ControlDocker))
    }

    // WHY: only server, address or key start a detection; a rename or accent change is not worth two requests.
    @Test fun unrelatedServerChangeDoesNotReprobe() = runTest {
        val script = Script(answer(viewer))
        val source = MutableStateFlow<ActiveServer?>(active(name = "Tower"))
        repo(source, script)
        runCurrent()
        source.value = active(name = "Renamed tower")
        runCurrent()
        assertEquals(1, script.calls)
    }

    @Test fun changedKeyOrAddressReprobes() = runTest {
        val script = Script(answer(viewer))
        val source = MutableStateFlow<ActiveServer?>(active())
        repo(source, script)
        runCurrent()
        source.value = active(key = "key-2")
        runCurrent()
        source.value = active(key = "key-2", url = "http://tower.example")
        runCurrent()
        assertEquals(3, script.calls)
    }

    // WHY: pull-to-refresh and foreground can arrive together; never two detections in parallel.
    @Test fun refreshJoinsRunningDetection() = runTest {
        val gate = CompletableDeferred<ProbeOutcome>()
        val script = Script({ gate.await() })
        val r = repo(MutableStateFlow(active()), script)
        runCurrent()
        r.refresh()
        r.refresh()
        runCurrent()
        assertEquals(1, script.calls)
        gate.complete(viewer)
        runCurrent()
        assertEquals(1, script.calls)
        assertEquals(Detection.Done, r.capabilities.value.detection)
    }

    // WHY: the user widens the key in the web UI (same key string); refresh must pick that up.
    @Test fun refreshAfterDone_detectsAgain_andShowsTheNewAccess() = runTest {
        val script = Script(answer(viewer), answer(admin))
        val r = repo(MutableStateFlow(active()), script)
        runCurrent()
        assertEquals(Access.Denied, r.capabilities.value.access(KeyAction.ControlVms))
        r.refresh()
        runCurrent()
        assertEquals(2, script.calls)
        assertEquals(Access.Allowed, r.capabilities.value.access(KeyAction.ControlVms))
    }

    // WHY: while a re-detection runs, the last good result stays (no flicker of buttons).
    @Test fun lastResultStaysVisibleWhileRedetecting() = runTest {
        val gate = CompletableDeferred<ProbeOutcome>()
        val script = Script(answer(viewer), { gate.await() })
        val r = repo(MutableStateFlow(active()), script)
        runCurrent()
        r.refresh()
        runCurrent()
        assertEquals(Detection.Done, r.capabilities.value.detection)
        assertEquals(Access.Denied, r.capabilities.value.access(KeyAction.ControlDocker))
        gate.complete(viewer)
    }

    // WHY: a transient error must not toggle controls.
    @Test fun failedRedetectionKeepsLastResult() = runTest {
        val script = Script(answer(viewer), answer(failed))
        val r = repo(MutableStateFlow(active()), script)
        runCurrent()
        val before = r.capabilities.value
        r.refresh()
        runCurrent()
        assertEquals(2, script.calls)
        assertEquals(before, r.capabilities.value)
    }

    // WHY: a first detection that fails is "unknown", so the UI fails open.
    @Test fun failedFirstDetection_isFailed_andUnknown() = runTest {
        val r = repo(MutableStateFlow(active()), Script(answer(failed)))
        runCurrent()
        val caps = r.capabilities.value
        assertEquals(Detection.Failed, caps.detection)
        assertTrue(KeyAction.entries.all { caps.access(it) == Access.Unknown })
    }

    @Test fun probeThatThrows_isFailed_notACrash() = runTest {
        val r = repo(MutableStateFlow(active()), Script({ throw IllegalStateException("boom") }))
        runCurrent()
        assertEquals(Detection.Failed, r.capabilities.value.detection)
    }

    // WHY: the server said UNAUTHENTICATED; the next detection with a fixed key must clear it.
    @Test fun keyRejected_thenAccepted() = runTest {
        val script = Script(answer(ProbeOutcome(Detection.KeyRejected)), answer(viewer))
        val r = repo(MutableStateFlow(active()), script)
        runCurrent()
        assertEquals(Detection.KeyRejected, r.capabilities.value.detection)
        r.refresh()
        runCurrent()
        assertEquals(Detection.Done, r.capabilities.value.detection)
    }

    // WHY: an answer for a server the user has already left must not land on the new one.
    @Test fun staleAnswerIsDropped() = runTest {
        val gateA = CompletableDeferred<ProbeOutcome>()
        val script = Script({ gateA.await() }, answer(viewer))
        val source = MutableStateFlow<ActiveServer?>(active("a", key = "ka"))
        val r = repo(source, script)
        runCurrent()
        source.value = active("b", url = "http://other", key = "kb")
        runCurrent()
        gateA.complete(admin) // A answers late
        runCurrent()
        assertEquals("b", r.capabilities.value.target?.serverId)
        assertEquals(Access.Denied, r.capabilities.value.access(KeyAction.ControlDocker))
    }

    @Test fun removingTheKeyResetsToUnknown() = runTest {
        val source = MutableStateFlow<ActiveServer?>(active())
        val r = repo(source, Script(answer(viewer)))
        runCurrent()
        source.value = active(key = "")
        runCurrent()
        assertEquals(Detection.Idle, r.capabilities.value.detection)
        assertTrue(KeyAction.entries.all { r.capabilities.value.access(it) == Access.Unknown })
    }

    // WHY: the probe must use the newest server settings (e.g. a certificate pin accepted after a failed
    // first detection) although the target is unchanged.
    @Test fun refreshUsesTheNewestServerSettings() = runTest {
        val script = Script(answer(failed), answer(viewer))
        val source = MutableStateFlow<ActiveServer?>(active())
        val r = repo(source, script)
        runCurrent()
        source.value = active().copy(localCertPin = "pinned")
        runCurrent()
        assertEquals(1, script.calls)
        r.refresh()
        runCurrent()
        assertEquals("pinned", script.seen.last().localCertPin)
    }

    // WHY: no new background work (the maintainer's rule): nothing re-detects by itself.
    @Test fun noProbeWithoutTrigger() = runTest {
        val script = Script(answer(viewer))
        repo(MutableStateFlow(active()), script)
        runCurrent()
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        assertEquals(1, script.calls)
    }

    // WHY: a failed detection must not turn into a retry loop either.
    @Test fun noRetryAfterFailure() = runTest {
        val script = Script(answer(failed))
        repo(MutableStateFlow(active()), script)
        runCurrent()
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        assertEquals(1, script.calls)
    }
}
