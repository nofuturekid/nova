package io.github.nofuturekid.nova.data.alerts

import kotlinx.serialization.Serializable

/** How many notification ids one server remembers. Oldest forgotten first beyond this. */
const val MAX_SEEN_NOTIFICATION_IDS = 300

/**
 * What NOVA remembers about one server so that an alert is raised once, not at every check.
 * Persisted per server (see [AlertStateStore]); [evaluateAlerts] produces the next value.
 *
 * The two halves are independent because the two reads can fail independently: a failed
 * read leaves its half exactly as it was.
 */
@Serializable
data class ServerAlertState(
    /** False until one notification read succeeded: that read only records, it posts nothing. */
    val notificationsBaselined: Boolean = false,
    /** Ids already reported or deliberately skipped, oldest first, at most [MAX_SEEN_NOTIFICATION_IDS]. */
    val seenNotificationIds: List<String> = emptyList(),
    /** False until one array read succeeded: that read only records, it posts nothing. */
    val conditionsBaselined: Boolean = false,
    /** Array conditions that held at the last successful read, a subset of [STATE_ALERT_KEYS]. */
    val openConditions: Set<String> = emptySet(),
)
