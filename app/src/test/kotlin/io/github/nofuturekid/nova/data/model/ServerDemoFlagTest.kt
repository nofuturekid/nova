package io.github.nofuturekid.nova.data.model

import io.github.nofuturekid.nova.data.demo.DemoFixtures
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHY: [Server.isDemo] is how the background alert check (and anything else that connects)
 * tells the demo from a real server, so it must survive the stored list; and a real server's
 * stored entry must stay exactly what it was before the demo mode existed.
 */
class ServerDemoFlagTest {
    // Same configuration as SettingsStore.
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theDemoFlagSurvivesTheStoredList() {
        val back = json.decodeFromString<List<Server>>(json.encodeToString(listOf(DemoFixtures.server)))
        assertTrue(back.single().isDemo)
    }

    @Test fun aRealServerIsStoredWithoutTheFlag() {
        val real = Server("s1", "Tower", "tower", "http://tower", "")
        assertFalse("isDemo" in json.encodeToString(real))
        assertFalse(json.decodeFromString<Server>("""{"id":"s1","name":"Tower","hostname":"tower","localUrl":"http://tower","remoteUrl":""}""").isDemo)
    }

    // WHY: no address, no key, no certificate: nothing to connect with, so nothing can leave the device.
    @Test fun theDemoEntryHasNoAddress() {
        val s = DemoFixtures.server
        assertTrue(s.localUrl.isEmpty() && s.remoteUrl.isEmpty() && !s.trustSelfSignedLocal)
    }
}
