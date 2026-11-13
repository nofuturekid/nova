package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.NotifImportance
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.UnraidNotification

/** Test data for the worker, scheduler and notifier tests. */

fun testServer(id: String, name: String) = Server(
    id = id, name = name, hostname = "$id.example", localUrl = "https://$id.example", remoteUrl = "",
)

fun unraidNotification(
    id: String,
    importance: NotifImportance = NotifImportance.Alert,
    subject: String = "Alert $id",
    description: String = "Details of $id",
    timestamp: String? = null,
) = UnraidNotification(
    id = id, title = "Title", subject = subject, description = description, importance = importance,
    type = null, link = null, timestamp = timestamp, formattedTimestamp = null,
)

fun okResult(items: List<UnraidNotification>, state: ArrayState = ArrayState.Started) = ProbeResult.Ok(
    Notifications(unreadWarning = 0, unreadAlert = items.size, unreadTotal = items.size, items = items),
    ArrayInfo(state, totalTb = 8.0, usedTb = 2.0, parity = null, disks = emptyList()),
)

/** A [ServerProbe] whose answer is computed per call, so a test can make one server fail or hang. */
class ScriptedProbe(private val script: suspend (Server) -> ProbeResult) : ServerProbe {
    val probed = mutableListOf<String>()
    override suspend fun probe(server: Server, pin: String?): ProbeResult {
        probed += server.id
        return script(server)
    }
}
