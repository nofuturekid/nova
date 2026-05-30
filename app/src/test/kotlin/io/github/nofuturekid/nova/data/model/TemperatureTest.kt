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
        // WHY: the `available` flag — not the numeric average — distinguishes a
        // real reading from absence. A typed sensor at 0 °C is plausible and kept.
        val t = selectTemperature(
            listOf(sample("CPU Core", SensorType.CpuCore, 0.0)),
        )
        assertTrue(t.available)
        assertEquals(0.0, t.average, EPS)
    }

    @Test fun real_server_fixture_excludes_fan_and_voltages_keeps_temps() {
        // WHY (the whole point of beta3): the server reports a fan tach and
        // voltage rails as CELSIUS sensors (all type CUSTOM). A fan must NEVER
        // be shown as the hottest temperature, and its bogus CRITICAL status
        // must NOT inflate the critical count. Fixture shaped like the real
        // 2026-05-30 server data.
        val out = selectTemperature(
            listOf(
                // ── junk that must be filtered out ──
                sample("it8613 CPU Fan", SensorType.Custom, 1753.0, TemperatureStatus.Critical), // fan RPM > 130
                sample("it8613 in0", SensorType.Custom, 1.68),  // voltage < 8
                sample("+3.3V", SensorType.Custom, 3.34),       // voltage < 8
                // ── real temperatures that must be kept ──
                sample("coretemp CPU Temp", SensorType.CpuCore, 52.0),
                sample("nvme Composite", SensorType.Nvme, 38.85),
                sample("it8613 MB Temp", SensorType.Custom, 42.0), // CUSTOM but in band
            ),
        )
        // The fan (max value 1753) must not win "hottest"; the real CPU does.
        assertEquals("coretemp CPU Temp", out.hottestName)
        assertEquals(52.0, out.hottestValue, EPS)
        // Average over ONLY the kept temps — the fan/voltages are gone.
        assertEquals((52.0 + 38.85 + 42.0) / 3.0, out.average, EPS)
        // The fan's false CRITICAL was filtered out, so the count is 0.
        assertEquals(0, out.criticalCount)
        assertEquals(0, out.warningCount)
        assertEquals(TemperatureUnit.Celsius, out.unit)
        assertTrue(out.available)
    }

    @Test fun custom_in_band_kept_but_typed_over_cap_dropped() {
        // WHY: the lower floor only gates CUSTOM (a typed low reading is real);
        // the upper cap gates ALL types (no real temperature exceeds 130 °C).
        val out = selectTemperature(
            listOf(
                sample("MB Temp", SensorType.Custom, 42.0),    // CUSTOM, in band → kept
                sample("Ambient", SensorType.Ambient, 6.0),    // typed below floor → kept
                sample("Glitch Core", SensorType.CpuCore, 999.0), // typed but > cap → dropped
            ),
        )
        // hottest is the in-band MB temp, NOT the 999 glitch.
        assertEquals("MB Temp", out.hottestName)
        assertEquals(42.0, out.hottestValue, EPS)
        assertEquals((42.0 + 6.0) / 2.0, out.average, EPS)
    }

    @Test fun warning_and_critical_counts_come_from_kept_set_only() {
        // WHY: counts must reflect only sensors that survived the filter, so the
        // card accent escalates on real heat, not on a mislabeled fan/voltage.
        val out = selectTemperature(
            listOf(
                sample("CPU Temp", SensorType.CpuCore, 88.0, TemperatureStatus.Warning),
                sample("NVMe", SensorType.Nvme, 95.0, TemperatureStatus.Critical),
                sample("Fan", SensorType.Custom, 1800.0, TemperatureStatus.Critical), // dropped
            ),
        )
        assertEquals(1, out.warningCount)
        assertEquals(1, out.criticalCount)
        assertEquals("NVMe", out.hottestName)
    }

    @Test fun empty_input_maps_to_unknown_sentinel() {
        // WHY: a null root → empty list, and an all-junk frame both yield NO real
        // temperature; that must read as 'unavailable', never a fabricated 0°.
        assertEquals(Temperature.UNKNOWN, selectTemperature(emptyList()))
    }

    @Test fun all_junk_input_maps_to_unknown_sentinel() {
        // WHY: if every channel is a fan/voltage, the kept set is empty — the
        // card must show 'unavailable' rather than the server's garbage summary.
        val out = selectTemperature(
            listOf(
                sample("CPU Fan", SensorType.Custom, 1753.0),
                sample("in0", SensorType.Custom, 1.68),
            ),
        )
        assertEquals(Temperature.UNKNOWN, out)
    }

    private fun sample(
        name: String,
        type: SensorType,
        value: Double,
        status: TemperatureStatus = TemperatureStatus.Normal,
    ) = TempSensorSample(name, type, value, TemperatureUnit.Celsius, status)

    private companion object { const val EPS = 1e-6 }
}
