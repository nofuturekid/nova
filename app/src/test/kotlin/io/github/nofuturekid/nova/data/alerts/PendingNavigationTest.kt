package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.AttentionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WHY: the main activity is exported, so any app can send it intents with these extras. A tap on
 * a NOVA notification must open the right server and place, and anything malformed must do
 * nothing rather than switch the user to a surprise state. A request that was handled must not
 * be handled again after a newer tap.
 */
class PendingNavigationTest {
    @Test fun a_valid_server_and_target_make_a_request() {
        assertEquals(
            NavigationRequest("srv-1", AttentionTarget.Array),
            NavigationRequest.from("srv-1", "Array"),
        )
        assertEquals(
            NavigationRequest("srv-1", AttentionTarget.Notifications),
            NavigationRequest.from("srv-1", "Notifications"),
        )
    }

    @Test fun anything_else_is_ignored() {
        assertNull(NavigationRequest.from(null, "Array"))
        assertNull(NavigationRequest.from("  ", "Array"))
        assertNull(NavigationRequest.from("srv-1", null))
        assertNull(NavigationRequest.from("srv-1", "Settings"))
        assertNull(NavigationRequest.from("srv-1", "array"))
    }

    @Test fun the_latest_tap_wins_and_consuming_an_old_request_leaves_a_newer_one() {
        val pending = PendingNavigation()
        val first = NavigationRequest("a", AttentionTarget.Array)
        val second = NavigationRequest("b", AttentionTarget.Notifications)
        pending.post(first)
        pending.post(second)

        pending.consume(first)
        assertEquals(second, pending.request.value)

        pending.consume(second)
        assertNull(pending.request.value)
    }
}
