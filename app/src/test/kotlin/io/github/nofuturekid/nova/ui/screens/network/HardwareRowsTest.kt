package io.github.nofuturekid.nova.ui.screens.network

import io.github.nofuturekid.nova.data.model.NetworkInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Why: #223 — stock API has no devices.network, so the Hardware block must vanish, not show blanks. */
class HardwareRowsTest {
    private fun iface(
        mac: String? = null, vendor: String? = null, model: String? = null,
        speed: String? = null, virtual: Boolean? = null,
    ) = NetworkInterface(
        name = "eth0", isPrimary = true, description = null, status = "up", protocol = null,
        macAddress = mac, ipAddress = "192.0.2.10", netmask = null, gateway = null, useDhcp = null,
        ipv6Address = null, ipv6Netmask = null, ipv6Gateway = null, useDhcp6 = null,
        vendor = vendor, model = model, speed = speed, virtual = virtual,
    )

    @Test fun nothing_known_hides_the_section() {
        assertTrue(hardwareRows(iface()).isEmpty())
    }

    @Test fun blank_strings_count_as_missing() {
        assertTrue(hardwareRows(iface(mac = " ", vendor = "", model = "", speed = "")).isEmpty())
    }

    @Test fun plugin_data_is_kept_in_order() {
        assertEquals(
            listOf("MAC" to "aa:bb", "Vendor" to "Intel", "Model" to "I225", "Link speed" to "2500", "Virtual" to "No"),
            hardwareRows(iface("aa:bb", "Intel", "I225", "2500", false)),
        )
    }
}
