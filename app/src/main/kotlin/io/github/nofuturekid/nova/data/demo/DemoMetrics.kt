package io.github.nofuturekid.nova.data.demo

import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.LiveMetrics
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.TemperatureStatus
import io.github.nofuturekid.nova.data.model.TemperatureUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * The live-looking figures of the demo (ADR-0050): pure functions of a tick number, so the same
 * tick always gives the same values (no random numbers, no hidden state). The numbers drift
 * like a quiet home server: waves of different lengths, small steps, never out of range.
 */
object DemoMetrics {
    const val TICK_MS = 2_000L

    fun live(n: Long): LiveMetrics {
        val t = n.toDouble()
        return LiveMetrics(
            cpuPercent = 16.0 + 9.0 * sin(t / 3.0) + (n % 5),
            memTotalGb = 31.3,
            memUsedGb = 11.4 + 1.4 * sin(t / 11.0),
            memBuffGb = 4.2,
        )
    }

    fun network(n: Long): NetworkThroughput {
        val t = n.toDouble()
        return NetworkThroughput(
            rxBytesPerSec = 2_000_000.0 + 1_400_000.0 * sin(t / 2.5) + 40_000.0 * (n % 7),
            txBytesPerSec = 700_000.0 + 400_000.0 * cos(t / 3.0),
        )
    }

    fun temperature(n: Long): Temperature {
        val t = n.toDouble()
        return Temperature(
            available = true,
            cpuC = 44.0 + 3.0 * sin(t / 3.0) + (n % 3),
            systemC = 37.0 + 1.5 * cos(t / 4.0),
            unit = TemperatureUnit.Celsius,
            cpuStatus = TemperatureStatus.Normal,
        )
    }

    /** The container's base figures, with the CPU share swinging around its resting value. */
    fun containerStats(base: ContainerLiveStats, n: Long, seed: Int): ContainerLiveStats {
        val swing = 1.0 + 0.3 * sin(n / 3.0 + seed)
        return base.copy(cpuPercent = (base.cpuPercent * swing).coerceAtLeast(0.0))
    }
}
