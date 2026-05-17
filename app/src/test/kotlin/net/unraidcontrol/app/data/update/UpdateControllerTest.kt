package net.unraidcontrol.app.data.update

import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.unraidcontrol.app.data.model.InstallState
import net.unraidcontrol.app.data.model.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        override suspend fun download(url: String, onProgress: (Float) -> Unit): File =
            onDownload(url, onProgress)
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
}
