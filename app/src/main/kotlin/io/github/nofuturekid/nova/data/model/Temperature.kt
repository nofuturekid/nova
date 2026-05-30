package io.github.nofuturekid.nova.data.model

/** Temperature display unit carried from the server — never assumed.
 *  [Unknown] is the sentinel value used when no reading is available. */
enum class TemperatureUnit { Celsius, Fahrenheit, Kelvin, Rankine, Unknown }

/**
 * Live temperature summary for the Overview card.
 *
 * [available] is the load-bearing flag: the `systemMetricsTemperature` root is
 * NULLABLE on the live server (introspection 2026-05-30), so absence must be
 * representable distinctly from a genuine 0-degree reading. When [available]
 * is false the card shows 'unavailable'; otherwise it shows [average] [unit].
 * [unit] is carried from the server (never hardcoded Celsius); it MAY be
 * [TemperatureUnit.Unknown] on an available reading whose hottest sensor is
 * absent — see [toTemperature]/the mapper tests for that intentional case.
 */
data class Temperature(
    val available: Boolean,
    val average: Double,
    val unit: TemperatureUnit,
    val hottestName: String,
    val hottestValue: Double,
    val warningCount: Int,
    val criticalCount: Int,
) {
    companion object {
        /** No-data sentinel for a null temperature root / null summary. */
        val UNKNOWN = Temperature(
            available = false,
            average = 0.0,
            unit = TemperatureUnit.Unknown,
            hottestName = "",
            hottestValue = 0.0,
            warningCount = 0,
            criticalCount = 0,
        )
    }
}

/** Plain, Apollo-free projection of a TemperatureSummary — the pure seam the
 *  Apollo mappers feed so [toTemperature] unit-tests without generated types
 *  (mirrors [IfaceSample] / [selectThroughput]). [unit] is already the domain
 *  enum so unit-carrying is exercised here, not buried in generated code. */
data class TempSummarySample(
    val average: Double?,
    val unit: TemperatureUnit,
    val hottestName: String?,
    val hottestValue: Double?,
    val warningCount: Int?,
    val criticalCount: Int?,
)

/**
 * The single Temperature mapping function, reused by BOTH the subscription
 * frame and the GetMetrics poll (zero mapper drift). A null [sample] or a null
 * [average] degrades to [Temperature.UNKNOWN] — the root is nullable on the
 * live server and a missing average must read as 'no data', never a fake 0.
 * A present [average] yields an AVAILABLE reading even when the hottest sensor
 * (and thus [unit]) is absent: the headline number, not the sensor, defines
 * availability (see usable_average_without_unit_is_available_with_unknown_unit).
 */
fun toTemperature(sample: TempSummarySample?): Temperature {
    val avg = sample?.average ?: return Temperature.UNKNOWN
    return Temperature(
        available = true,
        average = avg,
        unit = sample.unit,
        hottestName = sample.hottestName.orEmpty(),
        hottestValue = sample.hottestValue ?: 0.0,
        warningCount = sample.warningCount ?: 0,
        criticalCount = sample.criticalCount ?: 0,
    )
}
