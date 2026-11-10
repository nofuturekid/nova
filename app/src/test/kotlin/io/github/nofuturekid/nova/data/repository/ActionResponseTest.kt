package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.http.HttpEngine
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.runAction
import io.github.nofuturekid.nova.graphql.ArchiveNotificationMutation
import io.github.nofuturekid.nova.graphql.StopContainerMutation
import io.github.nofuturekid.nova.ui.screens.main.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * H1 — Apollo Kotlin 5's `execute()` never throws: a transport failure lands
 * in `response.exception`, GraphQL errors in `response.errors`. Every action
 * (mutation) used to ignore the response, so a refused or unreachable server
 * looked like success and [MainViewModel.launchAction] never showed its
 * snackbar (ADR-0037).
 *
 * These tests drive the REAL Apollo response path: an [ApolloClient] on a
 * scripted [HttpEngine] produces real `ApolloResponse`s (exception / errors /
 * data), and the production seams ([runAction], [UnraidRepository.restartContainer],
 * [UnraidRepository.fetchContainerLogs], [UnraidRepository.runNotificationActionFlow])
 * run on top of it. Fakes that THROW would hide the defect, because production
 * never throws here. Each test fails if the code under test ignores its response again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionResponseTest {

    /** Scripted server: answers each request with the next entry; records what was sent. */
    private class ScriptedEngine(private val replies: List<() -> HttpResponse>) : HttpEngine {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return replies[minOf(requests.size, replies.size) - 1]()
        }
        override fun close() {}
    }

    private fun json(body: String): () -> HttpResponse = {
        HttpResponse.Builder(200)
            .addHeader("Content-Type", "application/json")
            .body(Buffer().writeUtf8(body))
            .build()
    }

    private val unreachable: () -> HttpResponse = { throw IOException("connection refused") }
    private val forbidden = json("""{"errors":[{"message":"Forbidden: API key lacks DOCKER:UPDATE_ANY"}],"data":null}""")
    private val stopped = json("""{"data":{"docker":{"stop":{"id":"c1","state":"EXITED"}}}}""")
    private val started = json("""{"data":{"docker":{"start":{"id":"c1","state":"RUNNING"}}}}""")

    private fun client(engine: HttpEngine) =
        ApolloClient.Builder().serverUrl("http://unraid.test/graphql").httpEngine(engine).build()

    private suspend fun failureOf(block: suspend () -> Unit): ActionFailedException? =
        try { block(); null } catch (e: ActionFailedException) { e }

    // WHY: the whole defect — an unreachable server must fail the action, with the cause.
    @Test fun transportFailure_failsTheAction_withCause() = runTest {
        val e = failureOf { client(ScriptedEngine(listOf(unreachable))).runAction(StopContainerMutation("c1")) }
        assertNotNull("a mutation whose response carries an exception must throw", e)
        assertEquals("can't reach the server", e!!.message)
        assertTrue("technical cause stays on the exception", e.cause?.message.orEmpty().isNotEmpty())
    }

    // WHY: a permission error is a GraphQL error with HTTP 200; it must reach the user verbatim.
    @Test fun graphqlError_failsTheAction_withServerMessage() = runTest {
        val e = failureOf { client(ScriptedEngine(listOf(forbidden))).runAction(StopContainerMutation("c1")) }
        assertNotNull("a mutation whose response carries errors must throw", e)
        assertTrue(e!!.message, e.message!!.contains("API key lacks DOCKER:UPDATE_ANY"))
    }

    @Test fun success_doesNotThrow() = runTest {
        assertNull(failureOf { client(ScriptedEngine(listOf(stopped))).runAction(StopContainerMutation("c1")) })
    }

    // WHY: the user must see the honest cause, not the generic "couldn't reach" text,
    // when the server refused the action (ADR-0037, same texts as the query paths).
    @Test fun launcher_surfacesTheCause_notTheGenericMessage() = runTest {
        val messages = mutableListOf<String>()
        MainViewModel.runResilientAction(emit = { messages += it }) {
            client(ScriptedEngine(listOf(forbidden))).runAction(StopContainerMutation("c1"))
        }
        assertEquals(1, messages.size)
        assertTrue(messages[0], messages[0].contains("API key lacks DOCKER:UPDATE_ANY"))
    }

    // WHY: restart = stop + start. Starting a container whose stop failed (or an unreachable
    // server) is the wrong action; only ONE request may go out.
    @Test fun restart_afterFailedStop_doesNotSendStart() = runTest {
        val engine = ScriptedEngine(listOf(forbidden, started))
        val e = failureOf { UnraidRepository.restartContainer(client(engine), "c1") }
        assertNotNull(e)
        assertEquals("start must not be sent after a failed stop", 1, engine.requests.size)
    }

    // WHY: stop succeeded but start failed leaves the container down; the user must be told that.
    @Test fun restart_stopOkStartFailed_saysContainerIsStopped() = runTest {
        val engine = ScriptedEngine(listOf(stopped, forbidden))
        val e = failureOf { UnraidRepository.restartContainer(client(engine), "c1") }
        assertTrue(e?.message, e!!.message!!.contains("stopped but could not be started"))
    }

    // WHY: the seam only works if every mutation goes through runAction. Reads the
    // production source (Gradle runs unit tests with the module dir as working dir).
    @Test fun everyMutationGoesThroughRunAction() {
        val src = java.io.File("src/main/kotlin/io/github/nofuturekid/nova/data/repository/UnraidRepository.kt")
        assertTrue("source not found from ${java.io.File(".").absolutePath}", src.isFile)
        val uses = src.readLines().filter { !it.trim().startsWith("*") && !it.trim().startsWith("//") && Regex("""(^|[^A-Za-z])mutation\(""").containsMatchIn(it) }
        assertEquals("a bare mutation(...) bypasses response checking; use runAction: $uses", 1, uses.size)
        assertTrue(uses.single().contains("mutation(m).execute().orThrow()"))
    }

    @Test fun restart_onSuccess_sendsStopThenStart() = runTest {
        val engine = ScriptedEngine(listOf(stopped, started))
        UnraidRepository.restartContainer(client(engine), "c1")
        assertEquals(2, engine.requests.size)
    }

    // WHY: the documented contract of the notification flow — a failed mutation skips the
    // overview recalc AND the nudge. With a response-ignoring mutation both ran after a failure.
    @Test fun notificationAction_failedMutation_skipsRecalcAndNudge() = runTest {
        var recalc = false
        var nudged = false
        val c = client(ScriptedEngine(listOf(forbidden)))
        val e = failureOf {
            UnraidRepository.runNotificationActionFlow(
                resolveClient = { c },
                mutate = { it.runAction(ArchiveNotificationMutation("n1")) },
                recalculate = { recalc = true },
                nudge = { nudged = true },
            )
        }
        assertNotNull(e)
        assertFalse("recalc must not run after a failed mutation", recalc)
        assertFalse("nudge must not fire after a failed mutation", nudged)
    }

    private val logsOk = json(
        """{"data":{"docker":{"logs":{"lines":[{"timestamp":"t1","message":"hello"}],"cursor":null}}}}""",
    )
    private val logsEmpty = json("""{"data":{"docker":{"logs":{"lines":[],"cursor":null}}}}""")

    // WHY: the 3 s live tail replaces the visible logs with whatever this returns.
    // "failed" (null) must differ from "no logs" (empty) so one blip keeps the last good logs.
    @Test fun logs_failureIsNull_emptyIsEmpty_dataIsLines() = runTest {
        assertNull(UnraidRepository.fetchContainerLogs(client(ScriptedEngine(listOf(unreachable))), "c1", 10))
        assertNull(UnraidRepository.fetchContainerLogs(client(ScriptedEngine(listOf(forbidden))), "c1", 10))
        assertEquals(emptyList<Any>(), UnraidRepository.fetchContainerLogs(client(ScriptedEngine(listOf(logsEmpty))), "c1", 10))
        val lines = UnraidRepository.fetchContainerLogs(client(ScriptedEngine(listOf(logsOk))), "c1", 10)
        assertEquals(listOf("hello"), lines?.map { it.message })
    }

}
