package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.model.IfaceSample
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.selectThroughput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionMappingTest {
    @Test fun maps_each_frame_to_content() = runTest {
        val frames = listOf(
            listOf(IfaceSample("eth0", 100.0, 50.0)),
            listOf(IfaceSample("eth0", 200.0, 60.0)),
        )
        val out = UnraidRepository.subscriptionStreamForTest("https://x", frames) {
            selectThroughput(it, "eth0")
        }.toList()
        assertEquals(
            listOf(
                DomainState.Content(NetworkThroughput(100.0, 50.0), "https://x"),
                DomainState.Content(NetworkThroughput(200.0, 60.0), "https://x"),
            ),
            out,
        )
    }
}
