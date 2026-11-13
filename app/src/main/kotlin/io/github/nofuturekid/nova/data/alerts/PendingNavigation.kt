package io.github.nofuturekid.nova.data.alerts

import android.content.Intent
import io.github.nofuturekid.nova.data.model.AttentionTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Intent extra: the id of the server an alert notification is about. */
const val EXTRA_SERVER_ID = "nova.serverId"

/** Intent extra: where to land, the name of an [AttentionTarget]. */
const val EXTRA_TARGET = "nova.target"

/** "Open this server at this place", from a tap on an alert notification. */
data class NavigationRequest(val serverId: String, val target: AttentionTarget) {
    companion object {
        /**
         * The request an intent carries, or null when it carries none or a malformed one. The
         * main activity is exported, so any app can send these extras: unknown targets and a
         * blank server id are dropped here, and an unknown server id is dropped by the consumer.
         */
        fun from(intent: Intent?): NavigationRequest? = from(
            intent?.getStringExtra(EXTRA_SERVER_ID),
            intent?.getStringExtra(EXTRA_TARGET),
        )

        fun from(serverId: String?, target: String?): NavigationRequest? {
            if (serverId.isNullOrBlank()) return null
            val parsed = AttentionTarget.entries.firstOrNull { it.name == target } ?: return null
            return NavigationRequest(serverId, parsed)
        }
    }
}

/**
 * Hands a tap on an alert notification from the activity, which receives the intent, to the
 * main screen, which owns the selected server, tab and sheets. The latest request wins; the
 * consumer clears it with [consume] once it has acted on it.
 */
@Singleton
class PendingNavigation @Inject constructor() {
    private val _request = MutableStateFlow<NavigationRequest?>(null)
    val request: StateFlow<NavigationRequest?> = _request.asStateFlow()

    fun post(request: NavigationRequest) {
        _request.value = request
    }

    /** Clears [request] if it is still the pending one; a newer request stays. */
    fun consume(request: NavigationRequest) {
        _request.compareAndSet(request, null)
    }
}
