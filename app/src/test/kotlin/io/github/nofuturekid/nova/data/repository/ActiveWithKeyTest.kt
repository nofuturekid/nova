package io.github.nofuturekid.nova.data.repository

import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.local.ServerSnapshot
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.repository.ServerRepository.Companion.activeWithKeyFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHY: changing only the API key of the (single) active server leaves the server
 * list equal, so DataStore skips that write; without the key store's own flow in
 * the combine the running streams would keep polling with the old key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWithKeyTest {
    @Test fun keyOnlyChange_reEmitsWithNewKey() = runTest(UnconfinedTestDispatcher()) {
        val server = Server(id = "s1", name = "Tower", hostname = "tower", localUrl = "http://tower", remoteUrl = "")
        val keys = mutableMapOf("s1" to "old")
        val keyChanges = MutableStateFlow(keys.toMap())
        val out = mutableListOf<ActiveServer?>()
        val job = launch {
            activeWithKeyFlow(
                MutableStateFlow(ServerSnapshot(listOf(server), "s1", ConnectionMode.Local, emptyMap())),
                keyChanges,
            ) { ApiKeyResult.Present(keys.getValue(it)) }.toList(out)
        }
        keys["s1"] = "new"
        keyChanges.value = keys.toMap()
        job.cancel()
        assertEquals(listOf("old", "new"), out.map { it?.apiKey })
    }
}
