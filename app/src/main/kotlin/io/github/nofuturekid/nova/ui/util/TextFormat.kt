package io.github.nofuturekid.nova.ui.util

import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

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
internal fun memUsed(memUsage: String): String =
    memUsage.substringBefore(" / ").trim().ifEmpty { "—" }

internal fun memLimit(memUsage: String): String? =
    memUsage.substringAfter(" / ", "").trim().ifEmpty { null }

/**
 * VM list subtitle "4 vCPU · 8 GB · GPU". Each part is skipped while its
 * value is unknown (null); null when nothing is known, so the caller can
 * hide the line instead of showing "0 vCPU · 0 GB".
 */
internal fun vmSpecSummary(vcpus: Int?, memGb: Int?, gpu: String?): String? =
    listOfNotNull(vcpus?.let { "$it vCPU" }, memGb?.let { "$it GB" }, gpu)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

/** VM detail rows (label to value) for the values that are known, in display order. */
internal fun vmSpecRows(vcpus: Int?, memGb: Int?, gpu: String?): List<Pair<String, String>> =
    listOfNotNull(
        vcpus?.let { "vCPUs" to "$it" },
        memGb?.let { "Memory" to "$it GB" },
        gpu?.let { "GPU" to it },
    )

/**
 * Uptime for the System card. The API reports the BOOT TIME as an ISO-8601
 * timestamp ("2026-11-12T13:56:22.425Z"), not a duration, so the uptime is
 * [clock] now minus that time, in the compact form the app uses elsewhere
 * ("3d 4h", "23h 4m", "12m", "<1m"). A missing, unparsable or future
 * boot time gives "—": a wrong duration is worse than none.
 */
internal fun uptimeLabel(bootTime: String?, clock: Clock = Clock.systemUTC()): String {
    val boot = try {
        OffsetDateTime.parse(bootTime?.trim().orEmpty()).toInstant()
    } catch (_: DateTimeParseException) {
        return "—"
    }
    val up = Duration.between(boot, clock.instant())
    if (up.isNegative) return "—"
    val minutes = up.toMinutes()
    val days = minutes / (24 * 60)
    val hours = minutes / 60 % 24
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

/**
 * Disk row subtitle "sdb · Samsung SSD 870": device and model, only the parts
 * that are known. The stock API sends no model (it maps to ""), which used to
 * leave "sdb ·" on screen. Null when neither is known, so the caller can hide the line.
 */
internal fun diskSubtitle(device: String?, model: String?): String? =
    listOfNotNull(device?.trim()?.takeIf { it.isNotEmpty() }, model?.trim()?.takeIf { it.isNotEmpty() })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
