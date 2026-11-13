package io.github.nofuturekid.nova.ui.screens.overview

import io.github.nofuturekid.nova.data.model.ArrayInfo

/** What a card shows for a value that is not known (offline, not loaded yet). Same dash as the Network and Temperature cards. */
internal const val PLACEHOLDER = "—"

/** Sub line of a card whose live source is missing; same wording as the Network and Temperature cards. */
internal const val LIVE_UNAVAILABLE = "live unavailable"

/** Number of points a sparkline holds. */
internal const val SERIES_LENGTH = 40

/**
 * Texts and bar weights of the Array card. An unknown array ([array] null:
 * offline or not loaded yet) is not an empty array: it shows the placeholder
 * and an empty bar, while a real 0 TB from the server stays "0.0 / 0 TB".
 *
 * [barWeights] is (used, free) and null when the bar has nothing to say:
 * unknown array, or a total of 0 (no share can be drawn).
 */
internal data class ArrayFigures(
    val usedText: String,
    val totalText: String,
    val percentText: String,
    val freeText: String,
    val barWeights: Pair<Float, Float>?,
)

internal fun arrayFigures(array: ArrayInfo?): ArrayFigures {
    if (array == null) {
        return ArrayFigures(PLACEHOLDER, "TB used", PLACEHOLDER, PLACEHOLDER, barWeights = null)
    }
    val total = array.totalTb
    val used = array.usedTb
    val share = if (total > 0) (used / total).coerceIn(0.0, 1.0) else 0.0
    return ArrayFigures(
        usedText = "%.1f".format(used),
        totalText = "/ ${"%.0f".format(total)} TB used",
        percentText = "${(share * 100).toInt()}% used",
        freeText = "${"%.1f".format(total - used)} TB free",
        barWeights = if (total > 0) used.toFloat() to (total - used).coerceAtLeast(0.0).toFloat() else null,
    )
}

/** CPU headline: the load, or the placeholder while it is unknown. */
internal fun cpuValue(cpuPercent: Double?): String =
    cpuPercent?.let { "%.1f%%".format(it) } ?: PLACEHOLDER

/**
 * CPU sub line "8 cores · Ryzen 7 5800X": only the known parts. With nothing
 * known it shows [LIVE_UNAVAILABLE] while the load is also missing, else the placeholder.
 */
internal fun cpuSub(cores: Int?, brandTail: String?, loadKnown: Boolean): String =
    listOfNotNull(cores?.let { "$it cores" }, brandTail?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
        .ifEmpty { if (loadKnown) PLACEHOLDER else LIVE_UNAVAILABLE }

/** Texts and sparkline sample of the Memory card; every field falls back to the placeholder when its input is unknown. */
internal data class MemoryFigures(
    val value: String,
    val sub: String,
    /** Used share in percent for the sparkline, null when it cannot be computed. */
    val percent: Float?,
)

/**
 * [totalGb] is the OS-reported total, else the DIMM-slot sum; a total of 0 is
 * never a real memory size (some virtualised setups report it), so it counts as unknown.
 */
internal fun memoryFigures(usedGb: Double?, cachedGb: Double?, totalGb: Double?): MemoryFigures {
    val total = totalGb?.takeIf { it > 0.0 }
    val percent = if (usedGb != null && total != null) usedGb / total * 100 else null
    val sub = listOfNotNull(
        percent?.let { "${it.toInt()}% of ${"%.0f".format(total)} GB" },
        cachedGb?.let { "${"%.1f".format(it)} GB cached" },
    ).joinToString(" · ").ifEmpty { LIVE_UNAVAILABLE }
    return MemoryFigures(
        value = usedGb?.let { "%.1f GB".format(it) } ?: PLACEHOLDER,
        sub = sub,
        percent = percent?.toFloat(),
    )
}

/**
 * Next sparkline buffer. A missing sample ([sample] null) empties the buffer:
 * an unknown value is not a 0 on the graph. The first real sample fills the
 * whole buffer, so the graph starts as a flat line at that value.
 */
internal fun rollSeries(series: List<Float>, sample: Float?): List<Float> = when {
    sample == null -> emptyList()
    series.isEmpty() -> List(SERIES_LENGTH) { sample }
    else -> (series + sample).takeLast(SERIES_LENGTH)
}
