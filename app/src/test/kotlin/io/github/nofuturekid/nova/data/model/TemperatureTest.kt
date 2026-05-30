package io.github.nofuturekid.nova.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureTest {
    @Test fun unknown_sentinel_is_unavailable_and_unit_agnostic() {
        val t = Temperature.UNKNOWN
        // WHY: a null/absent temperature root must be visibly 'no data',
        // never an authoritative 0-degree reading (which would mislead).
        assertFalse(t.available)
        assertEquals(TemperatureUnit.Unknown, t.unit)
        assertEquals(0, t.warningCount)
        assertEquals(0, t.criticalCount)
        assertEquals("", t.hottestName)
    }

    @Test fun a_real_reading_is_available_even_at_zero_degrees() {
        // WHY: 0 degrees is a legitimate value; the `available` flag — not the
        // numeric average — is what distinguishes a real reading from absence.
        val t = Temperature(
            available = true, average = 0.0, unit = TemperatureUnit.Celsius,
            hottestName = "CPU", hottestValue = 0.0, warningCount = 0, criticalCount = 0,
        )
        assertTrue(t.available)
    }

    @Test fun full_summary_maps_with_unit_and_counts_carried() {
        // WHY: average + hottest + the warning/critical counts must all survive
        // mapping, and the unit must be carried from the server (Fahrenheit
        // here, NOT silently coerced to Celsius).
        val out = toTemperature(
            TempSummarySample(
                average = 41.5, unit = TemperatureUnit.Fahrenheit,
                hottestName = "CPU Package", hottestValue = 58.0,
                warningCount = 2, criticalCount = 1,
            ),
        )
        assertEquals(
            Temperature(
                available = true, average = 41.5, unit = TemperatureUnit.Fahrenheit,
                hottestName = "CPU Package", hottestValue = 58.0,
                warningCount = 2, criticalCount = 1,
            ),
            out,
        )
    }

    @Test fun null_summary_maps_to_unknown_sentinel() {
        // WHY: systemMetricsTemperature is NULLABLE — a null frame must degrade
        // to the no-data sentinel, never throw, never fabricate a 0° reading.
        assertEquals(Temperature.UNKNOWN, toTemperature(null))
    }

    @Test fun null_average_is_treated_as_no_data() {
        // WHY: a summary with no usable average is functionally absent;
        // surfacing it as 0 degrees would be a lie. Degrade to the sentinel.
        val out = toTemperature(
            TempSummarySample(
                average = null, unit = TemperatureUnit.Celsius,
                hottestName = "CPU", hottestValue = 50.0,
                warningCount = 0, criticalCount = 0,
            ),
        )
        assertEquals(Temperature.UNKNOWN, out)
    }

    @Test fun usable_average_without_unit_is_available_with_unknown_unit() {
        // WHY (intentional partial frame): a real average with NO hottest
        // sensor (so unit defaults to Unknown) is still a real reading — the
        // headline number drives `available`, not the sensor name. The card
        // shows the number with no unit symbol; it does NOT collapse to
        // 'unavailable'. Pins the documented available && unit==Unknown state.
        val out = toTemperature(
            TempSummarySample(
                average = 44.0, unit = TemperatureUnit.Unknown,
                hottestName = null, hottestValue = null,
                warningCount = 0, criticalCount = 0,
            ),
        )
        assertEquals(
            Temperature(
                available = true, average = 44.0, unit = TemperatureUnit.Unknown,
                hottestName = "", hottestValue = 0.0, warningCount = 0, criticalCount = 0,
            ),
            out,
        )
    }
}
