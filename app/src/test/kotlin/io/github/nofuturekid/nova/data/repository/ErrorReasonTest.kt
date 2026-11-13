package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.network.http.HttpEngine
import io.github.nofuturekid.nova.graphql.GetVmsQuery
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Why a tab failed (#247, #217): one case per rule of [classifyErrorReason], first match wins.
 *
 * The FORBIDDEN, UNAUTHENTICATED and validation bodies are the answers a real Unraid 7.4.0
 * server (API 4.37.11) gave on 2026-11-13, with the path or the unknown field adapted to the
 * query run here. The Docker and VM bodies are built from the message strings in the upstream
 * source (`catch-handlers.ts`, `vms.service.ts`): the test server has both services on, so
 * nobody has recorded them live. Every body goes through a real [ApolloClient] on a scripted
 * HTTP engine, so the real response path (including Apollo turning an HTTP 400
 * `application/graphql-response+json` body into `errors`) is what gets classified.
 */
class ErrorReasonTest {

    private class Engine(private val reply: () -> HttpResponse) : HttpEngine {
        override suspend fun execute(request: HttpRequest): HttpResponse = reply()
        override fun close() {}
    }

    private fun reply(body: String, status: Int = 200, type: String = "application/json"): () -> HttpResponse = {
        HttpResponse.Builder(status).addHeader("Content-Type", type).body(Buffer().writeUtf8(body)).build()
    }

    private val unauthenticated = reply(
        """{"errors":[{"message":"API key validation failed","locations":[{"line":1,"column":3}],"path":["vms"],"extensions":{"code":"UNAUTHENTICATED","originalError":{"message":"API key validation failed","error":"Unauthorized","statusCode":401}}}],"data":null}""",
    )
    private val validation400 = reply(
        """{"errors":[{"message":"Cannot query field \"domains\" on type \"Vms\".","locations":[{"line":1,"column":12}],"extensions":{"code":"GRAPHQL_VALIDATION_FAILED"}}]}""",
        status = 400,
        type = "application/graphql-response+json",
    )
    private val forbidden = reply(
        """{"errors":[{"message":"Forbidden resource","locations":[{"line":1,"column":10}],"path":["vms"],"extensions":{"code":"FORBIDDEN","originalError":{"message":"Forbidden resource","error":"Forbidden","statusCode":403}}}],"data":null}""",
    )
    private val dockerOff = reply(
        """{"errors":[{"message":"Docker socket unavailable.","path":["docker"],"extensions":{"code":"INTERNAL_SERVER_ERROR"}}],"data":{"docker":null}}""",
    )
    private val vmsOff = reply(
        """{"errors":[{"message":"Failed to retrieve VM domains: VMs are not available","path":["vms","domains"],"extensions":{"code":"INTERNAL_SERVER_ERROR"}}],"data":{"vms":{"domains":null}}}""",
    )
    private val resolverError = reply(
        """{"errors":[{"message":"boom","extensions":{"code":"INTERNAL_SERVER_ERROR"}}],"data":{"docker":null}}""",
    )
    private val unreachable: () -> HttpResponse = { throw IOException("connection refused") }

    /** Runs the VMs query like the poll does and classifies what comes back. */
    private suspend fun reasonOf(server: () -> HttpResponse): ErrorReason {
        val client = ApolloClient.Builder().serverUrl("http://unraid.test/graphql").httpEngine(Engine(server)).build()
        val resp = client.query(GetVmsQuery()).execute()
        return classifyErrorReason(resp.exception, resp.errors)
    }

    // WHY: #247. A server whose API is too old answers HTTP 400 with a validation error; the tab must
    // show the "API too old" hint, not raw validation text.
    @Test fun validationError_isApiTooOld() = runTest {
        assertEquals(ErrorReason.ApiTooOld, reasonOf(validation400))
    }

    // WHY: today a wrong key looks like "Can't reach the server"; the user must be told to fix the key.
    @Test fun unauthenticated_isKeyRejected() = runTest {
        assertEquals(ErrorReason.KeyRejected, reasonOf(unauthenticated))
    }

    // WHY: a key that may not read a domain is a permission problem, not an outage.
    @Test fun forbidden_isForbidden() = runTest {
        assertEquals(ErrorReason.Forbidden, reasonOf(forbidden))
    }

    // WHY: #217. A server with Docker or VMs turned off is healthy; that tab must say what is off.
    @Test fun dockerSocketUnavailable_isServiceOff() = runTest {
        assertEquals(ErrorReason.ServiceOff, reasonOf(dockerOff))
    }

    @Test fun vmsNotAvailable_isServiceOff() = runTest {
        assertEquals(ErrorReason.ServiceOff, reasonOf(vmsOff))
    }

    // WHY: an error nobody recognises must still show the server's own text and must not look like an outage.
    @Test fun otherError_isServerError() = runTest {
        assertEquals(ErrorReason.ServerError, reasonOf(resolverError))
    }

    // WHY: a transport failure is the only case where the pill may say Offline (rule 1 beats every body).
    @Test fun transportFailure_isUnreachable() = runTest {
        assertEquals(ErrorReason.Unreachable, reasonOf(unreachable))
    }

    // WHY: rule order. A response that carries both a key refusal and a service message is about the key.
    @Test fun firstMatchingRuleWins() {
        val both = listOf(
            com.apollographql.apollo.api.Error.Builder("Docker socket unavailable.").build(),
            com.apollographql.apollo.api.Error.Builder("API key validation failed")
                .extensions(mapOf("code" to "UNAUTHENTICATED")).build(),
        )
        assertEquals(ErrorReason.KeyRejected, classifyErrorReason(null, both))
        val transport: ApolloException = com.apollographql.apollo.exception.ApolloNetworkException("down")
        assertEquals(ErrorReason.Unreachable, classifyErrorReason(transport, both))
    }

    // WHY: the screen replaces the server's wording for a classified reason; the raw text must stay
    // reachable behind "Show details". For an unclassified error the server text IS the screen text.
    @Test fun graphQlError_keepsServerTextAsDetailOnlyWhenTheScreenReplacesIt() {
        val off = graphQlError(
            "Docker socket unavailable.",
            listOf(com.apollographql.apollo.api.Error.Builder("Docker socket unavailable.").build()),
        )
        assertEquals(ErrorReason.ServiceOff, off.reason)
        assertEquals("Docker socket unavailable.", off.detail)

        val plain = graphQlError("boom", listOf(com.apollographql.apollo.api.Error.Builder("boom").build()))
        assertEquals(ErrorReason.ServerError, plain.reason)
        assertNull(plain.detail)
    }

    // WHY: every producer that does not know a reason keeps today's "Can't reach the server" meaning.
    @Test fun errorWithoutReason_staysUnreachable() {
        assertEquals(ErrorReason.Unreachable, DomainState.Error("x").reason)
    }
}
