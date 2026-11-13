package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpEngine
import io.github.nofuturekid.nova.data.model.KeyAction
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.runAction
import io.github.nofuturekid.nova.graphql.StartContainerMutation
import io.github.nofuturekid.nova.graphql.StopVmMutation
import io.github.nofuturekid.nova.ui.screens.main.MainViewModel
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback path of #315: while the key's access is unknown the buttons are shown, and a refused
 * action must say WHY instead of "Forbidden resource". The FORBIDDEN body is the answer a real
 * Unraid 7.4.0 server (API 4.37.11) gave a VIEWER key for `docker.start` on 2026-11-13 (the id did
 * not exist and the guard ran before the resolver, so nothing changed); the VM and UNAUTHENTICATED
 * bodies have the same shape with the path adapted.
 */
class ActionRefusalTest {

    private class Engine(private val reply: () -> HttpResponse) : HttpEngine {
        override suspend fun execute(request: HttpRequest): HttpResponse = reply()
        override fun close() {}
    }

    private fun reply(body: String): () -> HttpResponse = {
        HttpResponse.Builder(200)
            .addHeader("Content-Type", "application/graphql-response+json")
            .body(Buffer().writeUtf8(body))
            .build()
    }

    private val forbiddenDocker = reply(
        """{"errors":[{"message":"Forbidden resource","locations":[{"line":1,"column":21}],"path":["docker","start"],"extensions":{"code":"FORBIDDEN","originalError":{"message":"Forbidden resource","error":"Forbidden","statusCode":403}}}],"data":null}""",
    )
    private val forbiddenVm = reply(
        """{"errors":[{"message":"Forbidden resource","locations":[{"line":1,"column":15}],"path":["vm","stop"],"extensions":{"code":"FORBIDDEN","originalError":{"message":"Forbidden resource","error":"Forbidden","statusCode":403}}}],"data":null}""",
    )
    private val unauthenticated = reply(
        """{"errors":[{"message":"API key validation failed","locations":[{"line":1,"column":21}],"path":["docker","start"],"extensions":{"code":"UNAUTHENTICATED","originalError":{"message":"API key validation failed","error":"Unauthorized","statusCode":401}}}],"data":null}""",
    )

    private fun client(engine: HttpEngine) =
        ApolloClient.Builder().serverUrl("http://unraid.test/graphql").httpEngine(engine).build()

    private suspend fun failureOf(block: suspend () -> Unit): ActionFailedException? =
        try { block(); null } catch (e: ActionFailedException) { e }

    // WHY: #315. A user who presses Restart with a read-only key learns which permission to grant,
    // not the server's internal "Forbidden resource".
    @Test fun forbiddenActionNamesPermission() = runTest {
        val e = failureOf { client(Engine(forbiddenDocker)).runAction(StartContainerMutation("c1")) }
        assertNotNull(e)
        assertEquals("your API key isn't allowed to do this (it needs DOCKER:UPDATE_ANY)", e!!.message)
        assertEquals("the server's own wording stays available for diagnosis", "Forbidden resource", e.detail)
    }

    @Test fun forbiddenVmActionNamesTheVmPermission() = runTest {
        val e = failureOf { client(Engine(forbiddenVm)).runAction(StopVmMutation("v1")) }
        assertEquals("your API key isn't allowed to do this (it needs VMS:UPDATE_ANY)", e!!.message)
    }

    // WHY: the snackbar prefix is added by the view model; the sentence must read as one sentence.
    @Test fun snackbarReadsAsOneSentence() = runTest {
        val messages = mutableListOf<String>()
        MainViewModel.runResilientAction(emit = { messages += it }) {
            client(Engine(forbiddenDocker)).runAction(StartContainerMutation("c1"))
        }
        assertEquals(
            listOf("Action failed: your API key isn't allowed to do this (it needs DOCKER:UPDATE_ANY)"),
            messages,
        )
    }

    @Test fun rejectedKeyIsSaidSo() = runTest {
        val e = failureOf { client(Engine(unauthenticated)).runAction(StartContainerMutation("c1")) }
        assertEquals("the server didn't accept the API key", e!!.message)
    }

    // WHY: a refusal for an operation NOVA has no table entry for must still be explained, without
    // inventing a permission name.
    @Test fun unknownOperationGetsTheGenericRefusal() {
        val errors = listOf(Error.Builder("Forbidden resource").extensions(mapOf("code" to "FORBIDDEN")).build())
        assertEquals("your API key isn't allowed to do this", actionRefusalMessage(errors, "SomethingNew"))
        assertEquals("your API key isn't allowed to do this", actionRefusalMessage(errors, null))
    }

    // WHY: only a refusal for the key is reworded. Any other server error keeps its own text, or the
    // user loses the one clue that explains it.
    @Test fun otherErrorsAreNotReworded() {
        val errors = listOf(Error.Builder("Failed to start").extensions(mapOf("code" to "INTERNAL_SERVER_ERROR")).build())
        assertNull(actionRefusalMessage(errors, "StartContainer"))
        assertNull(actionRefusalMessage(emptyList(), "StartContainer"))
        assertNull(actionRefusalMessage(null, "StartContainer"))
    }

    // WHY: the permission named in the message must be the one the buttons are hidden by. Every
    // mutation NOVA sends names exactly its KeyAction's permission, so the two cannot drift apart.
    @Test fun everyOperationNamesItsOwnPermission() {
        val errors = listOf(Error.Builder("Forbidden resource").extensions(mapOf("code" to "FORBIDDEN")).build())
        KeyAction.entries.forEach { action ->
            action.operations.forEach { op ->
                val text = actionRefusalMessage(errors, op)!!
                assertTrue("$op: $text", text.endsWith("(it needs ${action.permission})"))
                KeyAction.entries.filter { it != action }.forEach {
                    assertFalse("$op must not name ${it.permission}", text.contains(it.permission))
                }
            }
        }
    }
}
