package io.github.nofuturekid.nova.data.update

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import io.github.nofuturekid.nova.data.model.InstallState
import io.github.nofuturekid.nova.data.model.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [UpdateController] — the @Singleton install pipeline
 * extracted in ADR-0030 D2 (ADR-0012 revisit).
 *
 * These prove the install state machine and the single-owner semantics.
 * Each test states WHY it matters (Rule 9): if a transition or the
 * single-shared-state behaviour regresses, the corresponding test fails —
 * not just a smoke check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {

    @Before
    fun resetReceiver() { InstallStatusReceiver.resetForTest() }

    private val info = UpdateInfo(
        version = "1.2.3",
        tag = "v1.2.3",
        releaseUrl = "https://example/r",
        downloadUrl = "https://example/app.apk",
        sizeBytes = 1L,
        isPrerelease = false,
        releaseNotes = "",
    )

    /**
     * Fake install seam. Records the install call and lets each test pick
     * the download / install outcome. Defaults to a successful download +
     * a no-op install (the real terminal state then arrives via the
     * InstallStatusReceiver broadcast, mirroring production).
     */
    private class FakeInstaller(
        val onDownload: (suspend (String, (Float) -> Unit) -> File) = { _, p ->
            p(0.5f); p(1f); File("downloaded.apk")
        },
        val onInstall: (File) -> Unit = {},
    ) : ApkInstaller {
        var installedFile: File? = null
        override suspend fun download(
            url: String,
            expectedSizeBytes: Long,
            expectedDigest: String?,
            onProgress: (Float) -> Unit,
        ): File = onDownload(url, onProgress)
        override fun install(apk: File) {
            installedFile = apk
            onInstall(apk)
        }
    }

    /**
     * WHY: the happy path must walk Downloading(progress) → Installing,
     * then a system Success broadcast must land the controller back at
     * Idle. If installUpdate stopped emitting Downloading/Installing, or
     * if the broadcast collector stopped mapping Success→Idle, this fails.
     */
    @Test
    fun downloadingThenInstallingThenSuccess() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val controller = UpdateController(FakeInstaller(), scope)

        assertEquals(InstallState.Idle, controller.installState.value)

        controller.installUpdate(info)
        scope.testScheduler.advanceUntilIdle()

        // download() reported up to 1f then install() ran with no
        // NeedsPermission → state rests at Installing awaiting the
        // system broadcast.
        assertEquals(InstallState.Installing, controller.installState.value)

        InstallStatusReceiver.emitForTest(InstallEvent.UserConfirmShown)
        scope.testScheduler.advanceUntilIdle()
        assertEquals(InstallState.Installing, controller.installState.value)

        InstallStatusReceiver.emitForTest(InstallEvent.Success)
        scope.testScheduler.advanceUntilIdle()
        assertEquals(InstallState.Idle, controller.installState.value)
    }

    /**
     * WHY: a failed system install must surface InstallState.Failed with
     * the broadcast's message so the dialog shows the error. Regression
     * guard for the Failed branch of the events collector.
     */
    @Test
    fun systemBroadcastFailureSurfacesFailedState() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val controller = UpdateController(FakeInstaller(), scope)

        controller.installUpdate(info)
        scope.testScheduler.advanceUntilIdle()

        InstallStatusReceiver.emitForTest(InstallEvent.Failed("disk full"))
        scope.testScheduler.advanceUntilIdle()

        val s = controller.installState.value
        assertTrue(s is InstallState.Failed)
        assertEquals("disk full", (s as InstallState.Failed).message)
    }

    /**
     * WHY: when "install unknown apps" is not granted, install() throws
     * NeedsPermissionException and NO session is committed, so no
     * broadcast will ever arrive. The controller must move to
     * NeedsPermission (carrying the settings intent) rather than hang on
     * Installing forever.
     */
    @Test
    fun needsPermissionPathYieldsNeedsPermissionState() = runTest {
        val intent = Intent("test.settings")
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val controller = UpdateController(
            FakeInstaller(onInstall = {
                throw ApkInstaller.NeedsPermissionException(intent)
            }),
            scope,
        )

        controller.installUpdate(info)
        scope.testScheduler.advanceUntilIdle()

        val s = controller.installState.value
        assertTrue(s is InstallState.NeedsPermission)
        assertEquals(intent, (s as InstallState.NeedsPermission).intent)
    }

    /**
     * WHY: a thrown download error must terminate as Failed (not leave the
     * UI stuck on Downloading), and resetInstall() must clear it back to
     * Idle so the user can retry. Guards both the catch branch and reset.
     */
    @Test
    fun downloadFailureThenResetReturnsToIdle() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val controller = UpdateController(
            FakeInstaller(onDownload = { _, _ -> throw RuntimeException("network down") }),
            scope,
        )

        controller.installUpdate(info)
        scope.testScheduler.advanceUntilIdle()

        val failed = controller.installState.value
        assertTrue(failed is InstallState.Failed)
        assertEquals("network down", (failed as InstallState.Failed).message)

        controller.resetInstall()
        assertEquals(InstallState.Idle, controller.installState.value)
    }

    /**
     * WHY: this is the ADR-0012 revisit / ADR-0030 D2 behaviour change.
     * There is ONE shared install state. A single controller instance
     * observed by two callers reflects the same in-flight install to
     * both, with no per-observer `ownsInstall` isolation. If someone
     * reintroduced per-observer filtering, the second observer would not
     * see an install it didn't start and this assertion would fail.
     */
    @Test
    fun singleSharedStateIsVisibleToEveryObserver() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val controller = UpdateController(FakeInstaller(), scope)

        // Two independent "screens" read the one controller.
        val overviewState = controller.installState
        val settingsState = controller.installState

        controller.installUpdate(info)
        scope.testScheduler.advanceUntilIdle()
        InstallStatusReceiver.emitForTest(InstallEvent.UserConfirmShown)
        scope.testScheduler.advanceUntilIdle()

        // Same instance, same value — both screens reflect the install
        // regardless of which "started" it.
        assertEquals(InstallState.Installing, overviewState.value)
        assertEquals(InstallState.Installing, settingsState.value)
        assertEquals(overviewState, settingsState)
    }

    /**
     * Re-entrancy / double-tap (triage #22) — FIXED CONTRACT.
     *
     * GUARDED BEHAVIOUR: [UpdateController.installUpdate] now drops a
     * second call while an install is in flight. The guard at the top of
     * installUpdate only proceeds when `_installState` is terminal —
     * Idle (fresh) or Failed (retry) — and returns without launching when
     * it is non-terminal (Downloading / Installing / NeedsPermission).
     * A user double-tap on Install therefore produces exactly ONE
     * download and ONE PackageInstaller session, and the first install's
     * progress is NOT clobbered back to Downloading(0f).
     *
     * This test gates download() on a per-call latch so every step is
     * deterministic: hold invoke #1 mid-download at a known progress,
     * fire the double-tap, prove it was a no-op, release #1 to finish,
     * then prove a fresh installUpdate after a terminal state still
     * starts a new run (retry must keep working — the guard must not
     * permanently wedge the pipeline).
     *
     * DOC-NOTE (Rule 9): this is the regression sentinel for triage #22.
     * If the in-flight guard regresses (e.g. someone removes the early
     * return or makes it launch unconditionally again), the double-tap
     * starts a second download → `downloads`/`installs` go to 2 and the
     * clobber assertion flips → this test fails. Its failure is the
     * intended signal that the #22 guard was lost.
     */
    @Test
    fun secondInvokeWhileInFlightIsGuarded_noOp() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val downloads = AtomicInteger(0)
        val installs = AtomicInteger(0)
        // download() parks on a per-call gate so the in-flight window is
        // deterministic: hold invoke #1 mid-download at progress 0.5,
        // fire the double-tap while it is parked, then release.
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()

        val installer = object : ApkInstaller {
            override suspend fun download(
                url: String,
                expectedSizeBytes: Long,
                expectedDigest: String?,
                onProgress: (Float) -> Unit,
            ): File {
                val n = downloads.incrementAndGet()
                onProgress(0.5f) // advance off Downloading(0f) so a clobber is visible
                (if (n == 1) gate1 else gate2).await() // park this call
                onProgress(1f)
                return File("app-$n.apk")
            }
            override fun install(apk: File) { installs.incrementAndGet() }
        }
        val controller = UpdateController(installer, scope)

        // Invoke #1: enters download() and parks on gate1 at progress 0.5.
        controller.installUpdate(info)
        scope.testScheduler.advanceUntilIdle()
        // WHY: proves invoke #1 is genuinely in flight (one download
        // started, parked) and state advanced past Downloading(0f) — the
        // precondition for a true re-entrant double-tap, not a serial one.
        assertEquals(1, downloads.get())
        assertEquals(0, installs.get())
        assertEquals(
            "invoke #1 is mid-download at 0.5 before the double-tap",
            InstallState.Downloading(0.5f),
            controller.installState.value,
        )

        // Invoke #2 — the double-tap — while #1 is still parked on gate1.
        controller.installUpdate(info)
        scope.testScheduler.advanceUntilIdle()
        // WHY: GUARDED. installState is non-terminal (Downloading), so the
        // second call returns immediately without launching: NO second
        // download, NO extra install, and crucially the first install's
        // progress is NOT reset to Downloading(0f). This is the core
        // triage #22 fix — one user intent → one session.
        assertEquals(
            "double-tap must NOT start a second download (in-flight guard)",
            1,
            downloads.get(),
        )
        assertEquals(0, installs.get())
        assertEquals(
            "double-tap must NOT clobber invoke #1's progress",
            InstallState.Downloading(0.5f),
            controller.installState.value,
        )

        // Release invoke #1; let its single pipeline run to completion.
        gate1.complete(Unit)
        scope.testScheduler.advanceUntilIdle()
        // WHY: exactly one download and one install ran for the two taps —
        // the regression sentinel for triage #22. If the guard regresses
        // these become 2 and the test fails (intended signal).
        assertEquals(
            "guarded double-tap downloads once (triage #22 fix)",
            1,
            downloads.get(),
        )
        assertEquals(
            "guarded double-tap installs once (triage #22 fix)",
            1,
            installs.get(),
        )
        // WHY: install() returned with no NeedsPermission / broadcast yet,
        // so the controller rests at Installing awaiting the system
        // broadcast — recoverable, not wedged.
        assertEquals(InstallState.Installing, controller.installState.value)

        // RETRY MUST STILL WORK: drive the controller to a terminal state
        // (system failure → Failed) and prove a fresh installUpdate from a
        // terminal state DOES start a new run. The guard must gate only
        // in-flight re-entrancy, never permanently wedge the pipeline.
        InstallStatusReceiver.emitForTest(InstallEvent.Failed("system rejected"))
        scope.testScheduler.advanceUntilIdle()
        assertTrue(
            "precondition: controller is in a terminal Failed state",
            controller.installState.value is InstallState.Failed,
        )

        controller.installUpdate(info)
        scope.testScheduler.advanceUntilIdle()
        // WHY: from a terminal state (Failed) the guard lets the call
        // through, so a brand-new download starts. Proves retry-after-fail
        // still works — the guard is in-flight-only, not a one-shot latch.
        assertEquals(
            "installUpdate from a terminal Failed state starts a fresh run (retry works)",
            2,
            downloads.get(),
        )
        gate2.complete(Unit)
        scope.testScheduler.advanceUntilIdle()
        assertEquals(
            "the retried run installs (now exactly 2 total: 1 guarded + 1 retry)",
            2,
            installs.get(),
        )
    }
}
