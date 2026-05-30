package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.TemperatureUnit
import io.github.nofuturekid.nova.graphql.SystemMetricsTemperatureSubscription
import io.github.nofuturekid.nova.graphql.type.SensorType as GSensorType
import io.github.nofuturekid.nova.graphql.type.TemperatureStatus as GTemperatureStatus
import io.github.nofuturekid.nova.graphql.type.TemperatureUnit as GTemperatureUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureMappingTest {
    @Test fun null_root_maps_to_unknown_sentinel() {
        // WHY: systemMetricsTemperature is NULLABLE on the live server. A frame
        // whose root is null must degrade to the no-data sentinel without NPE —
        // the card shows 'unavailable', it does not crash.
        val data = SystemMetricsTemperatureSubscription.Data(systemMetricsTemperature = null)
        assertEquals(Temperature.UNKNOWN, data.toTemperature())
    }

    @Test fun real_server_frame_filters_fan_and_voltages_then_recomputes() {
        // WHY: the live server (2026-05-30) dumps EVERY lm-sensors channel here,
        // all mislabeled CELSIUS — a fan tach (1753 RPM, falsely CRITICAL) and
        // voltage rails (~1–3 V), all type CUSTOM. The server's own summary
        // names the fan as the hottest sensor; we must not. This pins that the
        // Apollo mapper selects sensors[], maps the domain enums through, and
        // hands a clean list to selectTemperature — proving a fan can never be
        // shown as the hottest temperature.
        val data = SystemMetricsTemperatureSubscription.Data(
            systemMetricsTemperature = SystemMetricsTemperatureSubscription.SystemMetricsTemperature(
                sensors = listOf(
                    sensor("it8613 CPU Fan", GSensorType.CUSTOM, 1753.0, GTemperatureStatus.CRITICAL),
                    sensor("it8613 in0", GSensorType.CUSTOM, 1.68, GTemperatureStatus.NORMAL),
                    sensor("+3.3V", GSensorType.CUSTOM, 3.34, GTemperatureStatus.NORMAL),
                    sensor("coretemp CPU Temp", GSensorType.CPU_CORE, 52.0, GTemperatureStatus.NORMAL),
                    sensor("nvme Composite", GSensorType.NVME, 38.85, GTemperatureStatus.NORMAL),
                    sensor("it8613 MB Temp", GSensorType.CUSTOM, 42.0, GTemperatureStatus.NORMAL),
                ),
            ),
        )
        val out = data.toTemperature()
        assertEquals("coretemp CPU Temp", out.hottestName)
        assertEquals(52.0, out.hottestValue, 1e-6)
        assertEquals((52.0 + 38.85 + 42.0) / 3.0, out.average, 1e-6)
        assertEquals(0, out.criticalCount) // fan's false CRITICAL filtered out
        assertEquals(TemperatureUnit.Celsius, out.unit)
    }

    @Test fun all_junk_frame_maps_to_unknown_sentinel() {
        // WHY: when every channel is a fan/voltage, nothing real remains — the
        // card must read 'unavailable', not the server's garbage summary.
        val data = SystemMetricsTemperatureSubscription.Data(
            systemMetricsTemperature = SystemMetricsTemperatureSubscription.SystemMetricsTemperature(
                sensors = listOf(
                    sensor("CPU Fan", GSensorType.CUSTOM, 1753.0, GTemperatureStatus.CRITICAL),
                    sensor("in0", GSensorType.CUSTOM, 1.68, GTemperatureStatus.NORMAL),
                ),
            ),
        )
        assertEquals(Temperature.UNKNOWN, data.toTemperature())
    }

    private fun sensor(
        name: String,
        type: GSensorType,
        value: Double,
        status: GTemperatureStatus,
    ) = SystemMetricsTemperatureSubscription.Sensor(
        name = name,
        type = type,
        current = SystemMetricsTemperatureSubscription.Current(
            value = value,
            unit = GTemperatureUnit.CELSIUS,
            status = status,
        ),
        warning = null,
        critical = null,
    )
}
