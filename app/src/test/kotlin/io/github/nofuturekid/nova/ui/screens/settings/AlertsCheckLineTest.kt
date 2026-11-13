package io.github.nofuturekid.nova.ui.screens.settings

import io.github.nofuturekid.nova.data.alerts.CheckOutcome
import io.github.nofuturekid.nova.data.alerts.CheckRecord
import io.github.nofuturekid.nova.data.alerts.testServer
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHY: the last-check line is what tells a user whether alerts really work for each server. It must
 * follow the server's current addresses, not only an old record: an http-only server is never
 * checked in the background, so no earlier "OK" may make it look covered.
 */
class AlertsCheckLineTest {
    private val tower = testServer("srv-tower", "Tower")
    private val plain = Server("srv-plain", "Plain", "plain.example", "http://plain.example", "")

    @Test fun every_outcome_has_its_own_words() {
        val texts = CheckOutcome.entries.map { outcomeText(it) }
        assertEquals("two outcomes must never read the same", texts.size, texts.toSet().size)
    }

    @Test fun a_checked_server_shows_the_outcome_and_the_time() {
        val line = serverCheckLine(tower, CheckRecord(5_000, CheckOutcome.Unreachable))

        assertEquals("could not be reached", line.text)
        assertEquals(5_000L, line.atEpochMs)
    }

    @Test fun a_server_never_checked_says_so() {
        assertEquals("not run yet", serverCheckLine(tower, null).text)
    }

    @Test fun an_http_only_server_is_not_encrypted_whatever_an_old_record_says() {
        val line = serverCheckLine(plain, CheckRecord(5_000, CheckOutcome.Ok))

        assertTrue(line.text.contains("connection is not encrypted"))
        assertTrue("one sentence says what is needed", line.text.contains("An https address is needed."))
        assertNull("it was not checked, so there is no time", line.atEpochMs)
    }

    @Test fun a_not_encrypted_record_of_a_server_that_has_https_now_is_out_of_date() {
        // The user added an https address after the last check.
        assertEquals("not run yet", serverCheckLine(tower, CheckRecord(5_000, CheckOutcome.NotEncrypted)).text)
    }

    @Test fun the_demo_server_is_never_checked() {
        val line = serverCheckLine(DemoFixtures.server, CheckRecord(5_000, CheckOutcome.Ok))

        assertEquals("demo server, never checked", line.text)
        assertNull(line.atEpochMs)
    }
}
