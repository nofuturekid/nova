package io.github.nofuturekid.nova.data.alerts

/** The check intervals a user can pick, in minutes. WorkManager's own floor for periodic work is 15. */
val ALERT_INTERVALS_MIN: List<Int> = listOf(15, 30, 60, 180)

const val DEFAULT_ALERT_INTERVAL_MIN = 30

val DEFAULT_ALERT_LEVEL = AlertLevel.AlertsAndWarnings

/**
 * The user's choices for alerts outside the app. [enabled] is false by default: until the
 * user switches it on, NOVA does no background work at all.
 */
data class AlertSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_ALERT_INTERVAL_MIN,
    val level: AlertLevel = DEFAULT_ALERT_LEVEL,
    /** True: check only on an unmetered network (Wi-Fi). False: any connected network. */
    val unmeteredOnly: Boolean = false,
)

/** [minutes] if it is one of [ALERT_INTERVALS_MIN], else the default. */
fun normalizeAlertInterval(minutes: Int): Int =
    if (minutes in ALERT_INTERVALS_MIN) minutes else DEFAULT_ALERT_INTERVAL_MIN
