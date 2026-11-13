package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.AttentionSeverity
import io.github.nofuturekid.nova.data.model.AttentionSnapshot
import io.github.nofuturekid.nova.data.model.AttentionTarget
import io.github.nofuturekid.nova.data.model.NotifImportance
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.UnraidNotification
import io.github.nofuturekid.nova.data.model.attentionItems

/**
 * The array conditions that become alerts. Unraid itself never notifies about these
 * (it stays silent about disks while the array is stopped), so NOVA raises them.
 *
 * Deliberately not widened to disk temperature, disk errors or parity items: Unraid
 * already sends those as notifications, and raising them again would post every
 * problem twice. A test fails if this set grows without that decision.
 */
val STATE_ALERT_KEYS: Set<String> = setOf("array-stopped", "array-degraded")

/** At most this many alerts are posted per server and check; the rest are folded into a summary. */
const val MAX_ALERTS_PER_CHECK = 5

/** What the user wants to hear about. Critical things are always included. */
enum class AlertLevel { AlertsOnly, AlertsAndWarnings }

/** The server an evaluation is for: the stable id keys the state, the name is for display. */
data class ServerRef(val id: String, val name: String)

/** Where an [Alert] comes from. */
enum class AlertSource { Notification, ArrayCondition }

/**
 * One alert to post now. [key] is the notification id or the condition key; it is
 * stable, so the poster can derive a stable Android notification id from server id + key.
 */
data class Alert(
    val key: String,
    val source: AlertSource,
    val severity: AttentionSeverity,
    val title: String,
    val text: String,
    /** The server's ISO timestamp for a notification; null for a condition. */
    val timestamp: String?,
    val target: AttentionTarget,
)

/**
 * Result of one evaluation. [toPost] holds at most [MAX_ALERTS_PER_CHECK] alerts, most
 * urgent first; [foldedCount] more qualified but are only worth a summary line
 * ([foldedSummaryText]). [nextState] must be stored whatever was posted: ids that were
 * folded or filtered out count as reported.
 */
data class AlertEvaluation(
    val server: ServerRef,
    val toPost: List<Alert>,
    val foldedCount: Int,
    val nextState: ServerAlertState,
)

/** The summary line for alerts that did not get their own notification, e.g. "and 3 more on Tower". */
fun foldedSummaryText(serverName: String, count: Int): String = "and $count more on $serverName"

/**
 * Decides which alerts to post after one check of one server, and what to remember.
 * Pure: no Android, no clock, no I/O.
 *
 * [notifications] and [array] are what the check returned; null means "not loaded this
 * time" (server unreachable, query failed). A null half changes nothing and posts nothing,
 * so alerts created during an outage are posted once the server answers again, and nothing
 * is posted twice.
 *
 * Notifications: every unread notification of importance alert or warning whose id is not
 * remembered is new. The very first successful read only remembers the ids, so switching
 * alerts on does not dump everything already unread on the user. An id is reported once,
 * even if it leaves the unread list and returns. A later notification with the same text
 * has a new id (Unraid builds it from the time) and is reported, as it should be.
 *
 * Array conditions: raised when they begin, not while they last, and again only after they
 * were gone at an in-between read. The rules are those of [attentionItems], wrapped and
 * restricted to [STATE_ALERT_KEYS], so the wording is the Overview card's own.
 *
 * [level]: [AlertLevel.AlertsOnly] posts only critical items. Items it filters out are still
 * remembered, so switching to [AlertLevel.AlertsAndWarnings] later does not post old ones.
 */
fun evaluateAlerts(
    server: ServerRef,
    previous: ServerAlertState,
    notifications: Notifications?,
    array: ArrayInfo?,
    level: AlertLevel,
): AlertEvaluation {
    val (conditionAlerts, afterConditions) = evaluateConditions(previous, array, level)
    val (notificationAlerts, next) = evaluateNotifications(afterConditions, notifications, level)
    // sortedByDescending is stable: equal severity keeps conditions before notifications.
    val ordered = (conditionAlerts + notificationAlerts).sortedByDescending { it.severity }
    return AlertEvaluation(
        server = server,
        toPost = ordered.take(MAX_ALERTS_PER_CHECK),
        foldedCount = (ordered.size - MAX_ALERTS_PER_CHECK).coerceAtLeast(0),
        nextState = next,
    )
}

private fun evaluateNotifications(
    previous: ServerAlertState,
    notifications: Notifications?,
    level: AlertLevel,
): Pair<List<Alert>, ServerAlertState> {
    if (notifications == null) return emptyList<Alert>() to previous
    val current = notifications.items.distinctBy { it.id }
    val remembered = previous.seenNotificationIds.toSet()
    val alerts = if (!previous.notificationsBaselined) {
        emptyList()
    } else {
        current.filter { it.id !in remembered }.mapNotNull { it.toAlert(level) }
    }
    // Ids still on the server go last, so only ids that are gone are forgotten first.
    val currentIds = current.map { it.id }
    val stillListed = currentIds.toSet()
    val seen = (previous.seenNotificationIds.filter { it !in stillListed } + currentIds)
        .takeLast(MAX_SEEN_NOTIFICATION_IDS)
    return alerts to previous.copy(notificationsBaselined = true, seenNotificationIds = seen)
}

private fun evaluateConditions(
    previous: ServerAlertState,
    array: ArrayInfo?,
    level: AlertLevel,
): Pair<List<Alert>, ServerAlertState> {
    if (array == null) return emptyList<Alert>() to previous
    val current = attentionItems(AttentionSnapshot(array, notifications = null, thresholds = null))
        .filter { it.key in STATE_ALERT_KEYS }
    val alerts = if (!previous.conditionsBaselined) {
        emptyList()
    } else {
        current
            .filter { it.key !in previous.openConditions && level.allows(it.severity) }
            .map {
                Alert(it.key, AlertSource.ArrayCondition, it.severity, it.title, it.detail, timestamp = null, target = it.target)
            }
    }
    return alerts to previous.copy(conditionsBaselined = true, openConditions = current.map { it.key }.toSet())
}

private fun UnraidNotification.toAlert(level: AlertLevel): Alert? {
    val severity = when (importance) {
        NotifImportance.Alert -> AttentionSeverity.Critical
        NotifImportance.Warning -> AttentionSeverity.Warning
        NotifImportance.Info -> return null
    }
    if (!level.allows(severity)) return null
    return Alert(
        key = id,
        source = AlertSource.Notification,
        severity = severity,
        title = subject.ifBlank { title }.ifBlank { "Server notification" },
        text = description,
        timestamp = timestamp,
        target = AttentionTarget.Notifications,
    )
}

private fun AlertLevel.allows(severity: AttentionSeverity): Boolean = when (this) {
    AlertLevel.AlertsOnly -> severity == AttentionSeverity.Critical
    AlertLevel.AlertsAndWarnings -> severity >= AttentionSeverity.Warning
}
