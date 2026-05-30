package io.github.nofuturekid.nova.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureTest {
    @Test fun unknown_sentinel_is_unavailable_and_unit_agnostic() {
        val t = Temperature.UNKNOWN
        // WHY: a null/absent temperature root must be visibly 'no data',
        // never an authoritative 0-degree reading (which would mislead).
        assertFalse(t.available)
        assertEquals(TemperatureUnit.Unknown, t.unit)
        assertNull(t.cpuC)
        assertNull(t.systemC)
        assertFalse(t.cpuWarning)
        assertFalse(t.cpuCritical)
    }

    @Test fun a_real_reading_is_available_even_at_zero_degrees() {
        // WHY: the `available` flag — not the numeric value — distinguishes a
        // real reading from absence. A typed sensor at 0 °C is plausible and kept.
        val t = selectTemperature(
            listOf(sample("CPU Core", SensorType.CpuCore, 0.0)),
        )
        assertTrue(t.available)
        assertEquals(0.0, t.cpuC!!, EPS)
    }

    @Test fun real_server_fixture_splits_cpu_from_system_and_excludes_junk() {
        // WHY (the whole point of beta4, building on beta3): the server reports a
        // fan tach and voltage rails as CELSIUS sensors (all type CUSTOM). A fan
        // must NEVER appear, its bogus CRITICAL must not escalate the card, and —
        // new in beta4 — the CPU must be SEPARABLE from the system so the card can
        // plot them as two lines. Fixture shaped like the real 2026-05-30 server.
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
        // CPU line = the only CPU-typed sensor; the fan (1753) can never win it.
        assertEquals(52.0, out.cpuC!!, EPS)
        // System line = mean of the kept NON-CPU temps only (NVME + in-band MB).
        // Fan (>130) and voltages (<8) are excluded, so they cannot pollute it.
        assertEquals(40.425, out.systemC!!, EPS)
        // The fan's false CRITICAL was filtered out → the CPU sensor is Normal.
        assertFalse(out.cpuCritical)
        assertFalse(out.cpuWarning)
        assertEquals(TemperatureUnit.Celsius, out.unit)
        assertTrue(out.available)
    }

    @Test fun cpu_line_is_the_hottest_cpu_typed_sensor() {
        // WHY: a multi-core/package box exposes several CPU sensors; the card's
        // CPU number must be the HOTTEST of them (the one that throttles), not an
        // average — and a non-CPU sensor hotter than the CPU must not steal it.
        val out = selectTemperature(
            listOf(
                sample("Package", SensorType.CpuPackage, 55.0),
                sample("Core 0", SensorType.CpuCore, 61.0),
                sample("Core 1", SensorType.CpuCore, 58.0),
                sample("NVMe", SensorType.Nvme, 70.0), // hotter, but NOT a CPU sensor
            ),
        )
        assertEquals(61.0, out.cpuC!!, EPS)
        assertEquals(70.0, out.systemC!!, EPS)
    }

    @Test fun cpu_status_drives_the_accent_signal() {
        // WHY: the card accent escalates on the CPU's health (the meaningful
        // number), so a WARNING/CRITICAL CPU sensor must surface as cpuWarning/
        // cpuCritical — and the chosen (hottest) CPU sensor's status is the one used.
        val warn = selectTemperature(
            listOf(sample("CPU", SensorType.CpuCore, 88.0, TemperatureStatus.Warning)),
        )
        assertTrue(warn.cpuWarning)
        assertFalse(warn.cpuCritical)

        val crit = selectTemperature(
            listOf(sample("CPU", SensorType.CpuPackage, 99.0, TemperatureStatus.Critical)),
        )
        assertTrue(crit.cpuCritical)
        assertFalse(crit.cpuWarning)
    }

    @Test fun no_cpu_sensor_yields_null_cpu_and_normal_status() {
        // WHY: a box with only ambient/disk sensors has no CPU number; the card
        // must omit the CPU side (cpuC null) and NEVER escalate the accent off a
        // non-CPU sensor — cpuStatus stays Normal regardless of system heat.
        val out = selectTemperature(
            listOf(
                sample("NVMe", SensorType.Nvme, 95.0, TemperatureStatus.Critical),
                sample("MB Temp", SensorType.Custom, 42.0),
            ),
        )
        assertNull(out.cpuC)
        assertFalse(out.cpuCritical)
        assertFalse(out.cpuWarning)
        assertEquals((95.0 + 42.0) / 2.0, out.systemC!!, EPS)
        assertTrue(out.available)
    }

    @Test fun no_non_cpu_sensor_yields_null_system() {
        // WHY: a CPU-only frame has no system ambient; the card must omit the
        // System side (systemC null) while still showing the CPU line.
        val out = selectTemperature(
            listOf(
                sample("Core 0", SensorType.CpuCore, 50.0),
                sample("Package", SensorType.CpuPackage, 48.0),
            ),
        )
        assertEquals(50.0, out.cpuC!!, EPS)
        assertNull(out.systemC)
        assertTrue(out.available)
    }

    @Test fun custom_in_band_kept_but_typed_over_cap_dropped() {
        // WHY: the lower floor only gates CUSTOM (a typed low reading is real);
        // the upper cap gates ALL types (no real temperature exceeds 130 °C). The
        // glitch CPU core (999) is dropped, so it cannot become the CPU line.
        val out = selectTemperature(
            listOf(
                sample("MB Temp", SensorType.Custom, 42.0),    // CUSTOM, in band → kept (system)
                sample("Ambient", SensorType.Ambient, 6.0),    // typed below floor → kept (system)
                sample("Glitch Core", SensorType.CpuCore, 999.0), // typed but > cap → dropped
            ),
        )
        // The 999 glitch is filtered, so there is NO valid CPU sensor.
        assertNull(out.cpuC)
        assertEquals((42.0 + 6.0) / 2.0, out.systemC!!, EPS)
    }

    @Test fun empty_input_maps_to_unknown_sentinel() {
        // WHY: a null root → empty list must read as 'unavailable', never a
        // fabricated 0°.
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
