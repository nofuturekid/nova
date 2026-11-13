package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpEngine
import io.github.nofuturekid.nova.data.api.CertificateUntrustedException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What the Add server sheet's Test button reports (#315). The probe is the capability detection
 * (version and `me`), not `Ping`: the server answers a bare `__typename` for ANY key, so the old
 * Test said "Connected" for a wrong key. Bodies are the verbatim answers of an Unraid 7.4.0 server
 * (API 4.37.11) recorded on 2026-11-13, except the explicit-permission one, which is constructed.
 * They run on a scripted HTTP engine through the real Apollo response path.
 */
class TestConnectionOutcomeTest {

    private class Engine(
        private val version: () -> HttpResponse,
        private val access: () -> HttpResponse,
    ) : HttpEngine {
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            return if ("GetApiVersion" in body) version() else access()
        }
        override fun close() {}
    }

    private fun reply(body: String, status: Int = 200, type: String = "application/json"): () -> HttpResponse = {
        HttpResponse.Builder(status).addHeader("Content-Type", type).body(Buffer().writeUtf8(body)).build()
    }

    private val versionOk = reply("""{"data":{"info":{"versions":{"core":{"unraid":"7.4.0","api":"4.37.11+8e587fe2"}}}}}""")
    private val viewerMe = reply("""{"data":{"me":{"roles":["VIEWER"],"permissions":[]}}}""")
    private val adminMe = reply("""{"data":{"me":{"roles":["ADMIN"],"permissions":[]}}}""")
    private val viewerWithDockerAndVms = reply(
        """{"data":{"me":{"roles":["VIEWER"],"permissions":[{"resource":"DOCKER","actions":["UPDATE_ANY"]},{"resource":"VMS","actions":["UPDATE_ANY"]}]}}}""",
    )
    private val unauthenticated = reply(
        """{"errors":[{"message":"API key validation failed","locations":[{"line":1,"column":3}],"path":["info"],"extensions":{"code":"UNAUTHENTICATED","originalError":{"message":"API key validation failed","error":"Unauthorized","statusCode":401}}}],"data":null}""",
    )
    private val forbidden = reply(
        """{"errors":[{"message":"Forbidden resource","locations":[{"line":1,"column":3}],"path":["me"],"extensions":{"code":"FORBIDDEN","originalError":{"message":"Forbidden resource","error":"Forbidden","statusCode":403}}}],"data":null}""",
    )
    private val validation400 = reply(
        """{"errors":[{"message":"Cannot query field \"core\" on type \"InfoVersions\".","locations":[{"line":1,"column":12}],"extensions":{"code":"GRAPHQL_VALIDATION_FAILED"}}]}""",
        status = 400,
        type = "application/graphql-response+json",
    )
    private val serverError = reply(
        """{"errors":[{"message":"boom","extensions":{"code":"INTERNAL_SERVER_ERROR"}}],"data":null}""",
    )

    private suspend fun test(version: () -> HttpResponse, access: () -> HttpResponse): TestOutcome =
        testOutcomeOf(probeCapabilities(client(Engine(version, access))))

    private fun client(engine: HttpEngine) =
        ApolloClient.Builder().serverUrl("http://unraid.test/graphql").httpEngine(engine).build()

    // WHY: the whole defect. A wrong key used to pass the Test, so the user saved a server that then
    // showed nothing; the server's UNAUTHENTICATED must fail it.
    @Test fun testConnectionRejectsWrongKey() = runTest {
        val out = test(unauthenticated, unauthenticated)
        assertEquals(TestOutcome.Failed("The server didn't accept this API key"), out)
    }

    // WHY: #315. A read-only key is a valid key, and the user is told up front that NOVA will hide
    // the controls, instead of finding that out from missing buttons.
    @Test fun testConnectionReportsViewOnly() = runTest {
        val out = test(versionOk, viewerMe) as TestOutcome.Ok
        assertEquals("Connected · Unraid 7.4.0 · API 4.37.11", out.title)
        assertEquals("View only key: NOVA will hide controls", out.detail)
        assertFalse(out.apiTooOld)
    }

    @Test fun testConnectionReportsFullControl() = runTest {
        val out = test(versionOk, adminMe) as TestOutcome.Ok
        assertEquals("Full control", out.detail)
    }

    // WHY: a least-privilege key (#316) must be described by what it can do, not as "view only".
    @Test fun testConnectionNamesWhatAPartialKeyCanControl() = runTest {
        val out = test(versionOk, viewerWithDockerAndVms) as TestOutcome.Ok
        assertEquals("Can control: Docker, VMs", out.detail)
    }

    // WHY: a refused `me` is fail-open everywhere; the Test must not claim "view only" for a key whose
    // rights nobody could read. It says nothing about control, and the connection still counts.
    @Test fun testConnectionSaysNothingAboutControlWhenAccessIsUnknown() = runTest {
        val out = test(versionOk, forbidden) as TestOutcome.Ok
        assertEquals(TestControl.Unknown, out.control)
        assertEquals("Server responded · ready to save", out.detail)
        assertEquals("Connected · Unraid 7.4.0 · API 4.37.11", out.title)
    }

    // WHY: #247. The server answers, so the connection is fine; what is wrong is the API version, and
    // the user is told what to update before they wonder why screens fail.
    @Test fun testConnectionWarnsWhenTheApiIsTooOld() = runTest {
        val out = test(validation400, viewerMe) as TestOutcome.Ok
        assertTrue(out.apiTooOld)
        assertEquals("Connected", out.title)
        assertEquals(
            "Connected, but this server's Unraid API is too old. NOVA needs 4.30.0 or newer (Unraid 7.2.5 or newer).",
            out.detail,
        )
    }

    // WHY: an unreachable server must fail the Test with the cause, never read as a working connection.
    @Test fun testConnectionFailsWhenTheServerIsUnreachable() = runTest {
        val unreachable: () -> HttpResponse = { throw IOException("connection refused") }
        val out = test(unreachable, unreachable)
        assertTrue(out.toString(), out is TestOutcome.Failed)
    }

    // WHY: the self-signed-certificate dialog depends on the Test surfacing the fingerprint. Moving the
    // Test off Ping must not swallow the certificate exception into a generic failure.
    @Test fun testConnectionStillSurfacesAnUntrustedCertificate() = runTest {
        val untrusted: () -> HttpResponse = { throw CertificateUntrustedException("AB:CD") }
        assertEquals(TestOutcome.CertUntrusted("AB:CD"), test(untrusted, untrusted))
    }

    // WHY: an error NOVA does not classify is shown with the server's own text, not as a success.
    @Test fun testConnectionShowsTheServersOwnErrorText() = runTest {
        assertEquals(TestOutcome.Failed("boom"), test(serverError, serverError))
    }

    private fun redirectTo(location: String): () -> HttpResponse = {
        HttpResponse.Builder(302).addHeader("Location", location).body(Buffer()).build()
    }

    // WHY: Unraid answers an http request with a 302 to its https address when SSL is Yes or Strict. A user who
    // typed such an address must be told the address to enter, in Test exactly as in the tabs, not "Http request
    // failed with status code 302".
    @Test fun testConnectionNamesTheRedirectTarget() = runTest {
        val moved = redirectTo("https://192-168-1-100.abc123.myunraid.net:8443/graphql?x=1")
        assertEquals(
            TestOutcome.Failed(
                "The server redirects to https://192-168-1-100.abc123.myunraid.net:8443. Edit the server and enter that address.",
            ),
            test(moved, moved),
        )
    }

    // WHY: a redirect without a usable target is still a redirect; Test says so instead of the generic status text.
    @Test fun testConnectionSaysARedirectWithoutTargetHasNoTarget() = runTest {
        val moved: () -> HttpResponse = { HttpResponse.Builder(302).body(Buffer()).build() }
        assertEquals(
            TestOutcome.Failed(
                "The server redirects to another address, but doesn't say which. Check the address in this server's settings.",
            ),
            test(moved, moved),
        )
    }
}
