package io.github.nofuturekid.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHY: three lines in the main manifest carry promises that nothing else would notice losing.
 * Without the removal of WorkManager's automatic initializer, WorkManager would start at every
 * app start and set its alarm and database with alerts off (the maintainer's rule is that nothing
 * runs then). Without the removal of FOREGROUND_SERVICE the permission list would grow by one NOVA
 * never uses. Without POST_NOTIFICATIONS no alert could be shown on Android 13 and later.
 *
 * Gradle runs unit tests with the module dir as working dir. The merged manifest is checked in
 * [WorkManagerStartupTest].
 */
class AlertsManifestTest {
    private val manifest: String by lazy {
        val main = File("src/main/AndroidManifest.xml")
        assertTrue("main manifest not found from ${File(".").absolutePath}", main.isFile)
        main.readText()
    }

    private fun tags(name: String) = Regex("""<$name\b[^>]*>""").findAll(manifest).map { it.value }.toList()

    @Test fun post_notifications_is_declared() {
        val declared = tags("uses-permission").filter { it.contains("android.permission.POST_NOTIFICATIONS") }
        assertEquals(1, declared.size)
        assertTrue(!declared.single().contains("tools:node=\"remove\""))
    }

    @Test fun the_foreground_service_permission_is_stripped_from_the_merged_manifest() {
        val entries = tags("uses-permission").filter { it.contains("android.permission.FOREGROUND_SERVICE") }
        assertTrue(entries.isNotEmpty() && entries.all { it.contains("tools:node=\"remove\"") })
    }

    @Test fun workmanagers_automatic_initializer_is_removed() {
        val initializer = tags("meta-data").filter { it.contains("androidx.work.WorkManagerInitializer") }
        assertEquals(1, initializer.size)
        assertTrue(initializer.single().contains("tools:node=\"remove\""))
    }

    @Test fun nova_declares_no_boot_receiver_service_exact_alarm_or_battery_exemption_of_its_own() {
        // The design relies on WorkManager's own, disabled-until-needed boot receiver and on the
        // periodic job; none of these is needed, and each would be one more thing to explain.
        assertTrue(tags("receiver").isEmpty())
        assertTrue(tags("service").isEmpty())
        val forbidden = listOf(
            "RECEIVE_BOOT_COMPLETED", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        )
        val declared = tags("uses-permission").filterNot { it.contains("tools:node=\"remove\"") }
        assertEquals(emptyList<String>(), declared.filter { tag -> forbidden.any { tag.contains(it) } })
    }
}
