package io.github.nofuturekid.nova.ui.util

/** "1 alert" / "2 alerts" — the app's strings are hard-coded (i18n is a separate TODO). */
internal fun countLabel(n: Int, singular: String, plural: String = "${singular}s"): String =
    "$n ${if (n == 1) singular else plural}"

/** Notification header: "1 alert · 1 warning · 2 info". */
internal fun notificationSummary(alerts: Int, warnings: Int, info: Int): String =
    "${countLabel(alerts, "alert")} · ${countLabel(warnings, "warning")} · $info info"

/**
 * Docker reports memory as one preformatted "used / limit" string
 * ("1.15GiB / 31.3GiB"). Narrow layouts show only the used part; the limit
 * is returned separately (null when the server sends no " / ").
 */
internal fun memUsed(memUsage: String): String = memUsage.substringBefore(" / ").trim()

internal fun memLimit(memUsage: String): String? =
    memUsage.substringAfter(" / ", "").trim().ifEmpty { null }
