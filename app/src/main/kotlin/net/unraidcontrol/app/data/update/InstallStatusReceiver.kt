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
                    events.tryEmit(InstallEvent.UserConfirmShown)
                } else {
                    events.tryEmit(InstallEvent.Failed("Confirm intent missing"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> events.tryEmit(InstallEvent.Success)
            else -> events.tryEmit(InstallEvent.Failed(message.ifBlank { "Install failed (code $status)" }))
        }
    }

    companion object {
        const val ACTION = "net.unraidcontrol.app.action.INSTALL_STATUS"

        private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
        val events get() = _events.asSharedFlow()
        private val events get() = _events
    }
}

sealed interface InstallEvent {
    data object UserConfirmShown : InstallEvent
    data object Success : InstallEvent
    data class Failed(val message: String) : InstallEvent
}
