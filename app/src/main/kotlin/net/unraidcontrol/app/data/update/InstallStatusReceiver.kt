package net.unraidcontrol.app.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Catches the PendingIntent fired by PackageInstaller when an install
 * session resolves. The receiver lives statically so the session callback
 * can post results onto a flow the ViewModel listens on.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the user to confirm. Pull the chained intent
                // and launch it as a new task so the ActivityResult flow can
                // proceed without an Activity in the stack.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) {
                    context.startActivity(confirm)
                    _events.tryEmit(InstallEvent.UserConfirmShown)
                } else {
                    _events.tryEmit(InstallEvent.Failed("Confirm intent missing"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> _events.tryEmit(InstallEvent.Success)
            else -> _events.tryEmit(InstallEvent.Failed(message.ifBlank { "Install failed (code $status)" }))
        }
    }

    companion object {
        const val ACTION = "net.unraidcontrol.app.action.INSTALL_STATUS"

        // replay = 1 so a collector that subscribes *after* the
        // PackageInstaller status broadcast has already fired (an in-process
        // cold-start / restart race during install — #10) still receives the
        // last install result instead of hanging forever on "Installing".
        // extraBufferCapacity is kept so the non-suspending tryEmit() in
        // onReceive() stays lossless.
        //
        // `var` (not `val`) only so resetForTest() can hand each test a
        // pristine instance; production never reassigns this field (no prod
        // code path calls resetForTest), so the single long-lived
        // UpdateController collector subscribes once at init to the original
        // instance and behaviour is byte-identical to a plain `val`.
        // @Volatile guarantees test-thread visibility of the reassignment.
        @Volatile
        private var _events = MutableSharedFlow<InstallEvent>(
            replay = 1,
            extraBufferCapacity = 8,
        )
        /**
         * Read-only stream consumers (e.g. MainViewModel) listen on. A getter
         * (not a cached val) so that after resetForTest() a freshly-built
         * (test) collector subscribes to the current `_events` instance. In
         * production `_events` is never reassigned, so this always returns the
         * original instance — unchanged production behaviour.
         */
        val events: kotlinx.coroutines.flow.SharedFlow<InstallEvent>
            get() = _events.asSharedFlow()

        /**
         * Test-only seam: emit an [InstallEvent] as if the system
         * PackageInstaller broadcast had arrived. Lets UpdateController's
         * broadcast-driven transitions be unit-tested without a real
         * install session (ADR-0030 D2). Not used by production code;
         * named `*ForTest` to mark intent (no annotation dep added).
         */
        fun emitForTest(event: InstallEvent): Boolean = _events.tryEmit(event)

        /**
         * Test-only seam: recreate the process-global flow so each test
         * starts with a fully pristine InstallStatusReceiver — both the
         * replay slot AND the extraBufferCapacity (#10) drained.
         *
         * Previously this called `_events.resetReplayCache()`, which clears
         * ONLY the replay slot, not the 8-slot extraBufferCapacity. The
         * triage-#22 concurrent test (two in-flight installUpdate calls →
         * double install → multiple events into the global flow) exposed
         * that buffered, uncollected emissions survived resetReplayCache()
         * and leaked into the next test's freshly-constructed
         * UpdateController collector → an order-dependent flaky failure.
         * Recreating the SharedFlow drops both replay and buffer, fully
         * isolating tests. Not used by production code (prod never resets
         * this seam; see `_events`/`events` notes — behaviour unchanged).
         */
        fun resetForTest() {
            _events = MutableSharedFlow(replay = 1, extraBufferCapacity = 8)
        }
    }
}

sealed interface InstallEvent {
    data object UserConfirmShown : InstallEvent
    data object Success : InstallEvent
    data class Failed(val message: String) : InstallEvent
}
