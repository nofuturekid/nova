package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.TemperatureUnit
import io.github.nofuturekid.nova.graphql.SystemMetricsTemperatureSubscription
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

    @Test fun populated_frame_carries_unit_and_counts() {
        // WHY: the server unit (Fahrenheit) must be carried through, not
        // assumed Celsius; warning/critical counts must survive to drive the
        // card accent.
        val current = SystemMetricsTemperatureSubscription.Current(value = 58.0, unit = GTemperatureUnit.FAHRENHEIT)
        val hottest = SystemMetricsTemperatureSubscription.Hottest(name = "CPU Package", current = current)
        val summary = SystemMetricsTemperatureSubscription.Summary(
            average = 41.5, hottest = hottest, warningCount = 2, criticalCount = 1,
        )
        val data = SystemMetricsTemperatureSubscription.Data(
            systemMetricsTemperature = SystemMetricsTemperatureSubscription.SystemMetricsTemperature(summary = summary),
        )
        assertEquals(
            Temperature(
                available = true, average = 41.5, unit = TemperatureUnit.Fahrenheit,
                hottestName = "CPU Package", hottestValue = 58.0,
                warningCount = 2, criticalCount = 1,
            ),
            data.toTemperature(),
        )
    }
}
