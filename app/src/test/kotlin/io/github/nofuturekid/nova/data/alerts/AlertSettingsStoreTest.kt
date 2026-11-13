package io.github.nofuturekid.nova.data.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nofuturekid.nova.data.local.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WHY: alerts are opt-in. The default must be off and must survive a store that has never held
 * these keys (every existing install), and a stored value that is not one of the offered intervals
 * must fall back to the default instead of reaching WorkManager, which rejects a period under
 * 15 minutes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AlertSettingsStoreTest {
    @get:Rule val stallWatch = StallWatch()

    private lateinit var settings: SettingsStore

    @Before fun setUp() {
        settings = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
    }

    @After fun tearDown() = runBlocking {
        settings.setAlertSettings(AlertSettings())
    }

    @Test fun the_defaults_are_off_thirty_minutes_alerts_and_warnings_any_network() = runBlocking {
        assertEquals(AlertSettings(false, 30, AlertLevel.AlertsAndWarnings, false), AlertSettings())
        settings.setAlertSettings(AlertSettings())
        assertEquals(AlertSettings(), settings.alertSettings.first())
    }

    @Test fun choices_survive_a_restart() = runBlocking {
        val chosen = AlertSettings(true, 180, AlertLevel.AlertsOnly, true)
        settings.setAlertSettings(chosen)

        val reopened = SettingsStore(ApplicationProvider.getApplicationContext<Context>())

        assertEquals(chosen, reopened.alertSettings.first())
    }

    @Test fun the_switch_alone_can_be_flipped_and_keeps_the_other_choices() = runBlocking {
        settings.setAlertSettings(AlertSettings(true, 60, AlertLevel.AlertsOnly, true))

        settings.setAlertsEnabled(false)

        assertEquals(AlertSettings(false, 60, AlertLevel.AlertsOnly, true), settings.alertSettings.first())
    }

    @Test fun an_interval_that_is_not_offered_falls_back_to_the_default() = runBlocking {
        settings.setAlertSettings(AlertSettings(true, intervalMinutes = 5))

        assertEquals(DEFAULT_ALERT_INTERVAL_MIN, settings.alertSettings.first().intervalMinutes)
        assertEquals(listOf(15, 30, 60, 180), ALERT_INTERVALS_MIN)
    }
}
