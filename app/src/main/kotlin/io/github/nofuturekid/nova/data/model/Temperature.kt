package io.github.nofuturekid.nova.data.model

/** Temperature display unit carried from the server — never assumed.
 *  [Unknown] is the sentinel value used when no reading is available. */
enum class TemperatureUnit { Celsius, Fahrenheit, Kelvin, Rankine, Unknown }

/** What kind of sensor a channel is, per the server's classification.
 *  [Custom] is the catch-all the Super-I/O chip dumps temps, voltages AND
 *  fan tachs into — the reason [selectTemperature] needs a plausibility band
 *  (see its KDoc). [Unknown] tolerates a future/unmapped server enum. */
enum class SensorType {
    CpuPackage, CpuCore, Motherboard, Chipset, Gpu, Disk, Nvme, Ambient, Vrm, Custom, Unknown
}

/** Per-sensor health the server attaches to a reading. Drives the card's
 *  warning/critical accent. [Unknown] tolerates a future/unmapped value. */
enum class TemperatureStatus { Normal, Warning, Critical, Unknown }

/**
 * Live temperature summary for the Overview card.
 *
 * [available] is the load-bearing flag: the `systemMetricsTemperature` root is
 * NULLABLE on the live server (introspection 2026-05-30), so absence must be
 * representable distinctly from a genuine 0-degree reading. When [available]
 * is false the card shows 'unavailable'; otherwise it shows [average] [unit].
 * [unit] is carried from the server (never hardcoded Celsius); it MAY be
 * [TemperatureUnit.Unknown] when the kept set is somehow unit-less — see
 * [selectTemperature]/the mapper tests for that intentional case.
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
        /** No-data sentinel: null root, or no plausible temperature sensor. */
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

/** Plain, Apollo-free projection of one temperature channel — the pure seam
 *  the Apollo mappers feed so [selectTemperature] unit-tests without generated
 *  types (mirrors [IfaceSample] / [selectThroughput]). [type], [unit] and
 *  [status] are already the domain enums so the enum-carrying is exercised
 *  here, not buried in generated code. */
data class TempSensorSample(
    val name: String,
    val type: SensorType,
    val value: Double,
    val unit: TemperatureUnit,
    val status: TemperatureStatus,
)

/** Below this a "temperature" reading is really a voltage rail (in0…in5,
 *  Vbat, +3.3V read ~1–3.5 V). Typed sensors (CPU_CORE/NVME/…) are trusted,
 *  but a [SensorType.Custom] channel under this floor is junk. */
const val MIN_PLAUSIBLE_C = 8.0

/** Above this a "temperature" reading is really a fan tach (RPM, e.g. the
 *  it8613 CPU Fan reading 1753). Applies to ALL types — even a typed sensor
 *  past this sanity cap is not a real ambient/core temperature. */
const val MAX_PLAUSIBLE_C = 130.0

/**
 * Recompute the Overview temperature summary from the raw sensor list,
 * discarding the server-computed summary entirely.
 *
 * WHY this exists: the Unraid server reports EVERY lm-sensors channel under
 * `systemMetricsTemperature`, all mislabeled `CELSIUS` — including a fan tach
 * (value = RPM, e.g. 1753) and voltage rails (value = Volts, ~1–3.5), all
 * with `type: CUSTOM`. Its server-side summary therefore names the fan as the
 * "hottest" sensor and inflates the average. We must never show a fan as the
 * hottest temperature.
 *
 * Filter rule — keep a sample as a real temperature iff:
 *   `value <= MAX_PLAUSIBLE_C && (type != Custom || value >= MIN_PLAUSIBLE_C)`.
 * The upper cap drops fan RPM regardless of type; the lower floor drops
 * voltage rails, but only for the untyped [SensorType.Custom] catch-all —
 * a typed sensor genuinely reading low (e.g. an 8 °C ambient) is kept.
 *
 * From the kept set: hottest = max value (carrying its name + value + unit);
 * average = mean of kept values; warning/criticalCount = kept samples whose
 * [status] is Warning / Critical; unit = the hottest kept sample's unit.
 * Empty kept set → [Temperature.UNKNOWN] (the card shows 'unavailable').
 */
fun selectTemperature(samples: List<TempSensorSample>): Temperature {
    val kept = samples.filter { s ->
        s.value <= MAX_PLAUSIBLE_C &&
            (s.type != SensorType.Custom || s.value >= MIN_PLAUSIBLE_C)
    }
    val hottest = kept.maxByOrNull { it.value } ?: return Temperature.UNKNOWN
    return Temperature(
        available = true,
        average = kept.sumOf { it.value } / kept.size,
        unit = hottest.unit,
        hottestName = hottest.name,
        hottestValue = hottest.value,
        warningCount = kept.count { it.status == TemperatureStatus.Warning },
        criticalCount = kept.count { it.status == TemperatureStatus.Critical },
    )
}
