package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpEngine
import io.github.nofuturekid.nova.data.model.Access
import io.github.nofuturekid.nova.data.model.ApiVersion
import io.github.nofuturekid.nova.data.model.Detection
import io.github.nofuturekid.nova.data.model.KeyAccess
import io.github.nofuturekid.nova.data.model.KeyAction
import io.github.nofuturekid.nova.data.model.Support
import io.github.nofuturekid.nova.data.model.ApiFeature
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * ADR-0047. versionOk, viewerMe, adminMe and unauthenticated are copied verbatim from real answers
 * of an Unraid 7.4.0 server (API 4.37.11) recorded on 2026-11-13; forbidden and validation400 keep
 * the real shape with the path or field adapted to these operations; resolverError and the
 * explicit-permission and FUTURE_ROLE bodies are constructed. They run on a scripted HTTP engine, i.e. through the real Apollo response path.
 */
class CapabilityProbeTest {

    private class Engine(
        private val version: () -> HttpResponse,
        private val access: () -> HttpResponse,
    ) : HttpEngine {
        val operations = mutableListOf<String>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            return when {
                "GetApiVersion" in body -> { operations += "GetApiVersion"; version() }
                "GetKeyAccess" in body -> { operations += "GetKeyAccess"; access() }
                else -> error("unexpected operation: $body")
            }
        }
        override fun close() {}
    }

    private fun reply(body: String, status: Int = 200, type: String = "application/json"): () -> HttpResponse = {
        HttpResponse.Builder(status).addHeader("Content-Type", type).body(Buffer().writeUtf8(body)).build()
    }

    private val versionOk = reply("""{"data":{"info":{"versions":{"core":{"unraid":"7.4.0","api":"4.37.11+8e587fe2"}}}}}""")
    private val viewerMe = reply("""{"data":{"me":{"roles":["VIEWER"],"permissions":[]}}}""")
    private val adminMe = reply("""{"data":{"me":{"roles":["ADMIN"],"permissions":[]}}}""")
    private val unauthenticated = reply(
        """{"errors":[{"message":"API key validation failed","locations":[{"line":1,"column":3}],"path":["me"],"extensions":{"code":"UNAUTHENTICATED","originalError":{"message":"API key validation failed","error":"Unauthorized","statusCode":401}}}],"data":null}""",
    )
    private val forbidden = reply(
        """{"errors":[{"message":"Forbidden resource","locations":[{"line":1,"column":3}],"path":["me"],"extensions":{"code":"FORBIDDEN","originalError":{"message":"Forbidden resource","error":"Forbidden","statusCode":403}}}],"data":null}""",
    )
    private val validation400 = reply(
        """{"errors":[{"message":"Cannot query field \"core\" on type \"InfoVersions\".","locations":[{"line":1,"column":12}],"extensions":{"code":"GRAPHQL_VALIDATION_FAILED"}}]}""",
        status = 400,
        type = "application/graphql-response+json",
    )
    private val resolverError = reply(
        """{"errors":[{"message":"boom","extensions":{"code":"INTERNAL_SERVER_ERROR"}}],"data":null}""",
    )
    private val unreachable: () -> HttpResponse = { throw IOException("connection refused") }

    private fun client(engine: HttpEngine) =
        ApolloClient.Builder().serverUrl("http://unraid.test/graphql").httpEngine(engine).build()

    private suspend fun probe(version: () -> HttpResponse, access: () -> HttpResponse): Pair<ProbeOutcome, Engine> {
        val engine = Engine(version, access)
        return probeCapabilities(client(engine)) to engine
    }

    // WHY: #315 — a read-only key reads everything and must not be offered buttons.
    @Test fun viewerKey_isDetected_withVersion() = runTest {
        val (out, _) = probe(versionOk, viewerMe)
        assertEquals(Detection.Done, out.detection)
        assertEquals("7.4.0", out.unraidVersion)
        assertEquals("4.37.11+8e587fe2", out.apiVersionText)
        assertEquals(ApiVersion(4, 37, 11), out.apiVersion)
        assertEquals(KeyAccess.Known(setOf("VIEWER"), emptySet()), out.access)
        val caps = out.toCapabilities(null)
        assertTrue(KeyAction.entries.all { caps.access(it) == Access.Denied })
        assertEquals(Support.Yes, caps.supports(ApiFeature.DockerRestart))
    }

    @Test fun adminKey_isDetected() = runTest {
        val (out, _) = probe(versionOk, adminMe)
        assertTrue(KeyAction.entries.all { out.toCapabilities(null).access(it) == Access.Allowed })
    }

    // WHY: explicit permissions are reported as RESOURCE:ACTION, the same spelling KeyAction uses.
    @Test fun explicitPermissions_becomeGrants() = runTest {
        val me = reply("""{"data":{"me":{"roles":["VIEWER"],"permissions":[{"resource":"DOCKER","actions":["UPDATE_ANY","READ_ANY"]},{"resource":"VMS","actions":["UPDATE_OWN"]}]}}}""")
        val (out, _) = probe(versionOk, me)
        assertEquals(
            KeyAccess.Known(setOf("VIEWER"), setOf("DOCKER:UPDATE_ANY", "DOCKER:READ_ANY", "VMS:UPDATE_OWN")),
            out.access,
        )
    }

    // WHY: a role a newer Unraid adds must stay "unknown", never "denied". Apollo hands an enum value that
    // is not in the client schema over as UNKNOWN__ (the raw name is not kept), which the role table
    // does not know either, so the answer is Unknown.
    @Test fun futureRole_staysUnknown() = runTest {
        val me = reply("""{"data":{"me":{"roles":["FUTURE_ROLE"],"permissions":[]}}}""")
        val (out, _) = probe(versionOk, me)
        assertEquals(Detection.Done, out.detection)
        assertEquals(KeyAccess.Known(setOf("UNKNOWN__"), emptySet()), out.access)
        assertTrue(KeyAction.entries.all { out.toCapabilities(null).access(it) == Access.Unknown })
    }

    // WHY: the same, next to a known role that grants nothing: still "unknown", not "denied".
    @Test fun futureRoleNextToViewer_staysUnknown() = runTest {
        val me = reply("""{"data":{"me":{"roles":["VIEWER","FUTURE_ROLE"],"permissions":[]}}}""")
        val (out, _) = probe(versionOk, me)
        assertTrue(KeyAction.entries.all { out.toCapabilities(null).access(it) == Access.Unknown })
    }

    // WHY: #315 "If the permission query fails, the app behaves as today": a refused `me` hides nothing.
    @Test fun forbiddenMe_failsOpen_andKeepsTheVersion() = runTest {
        val (out, _) = probe(versionOk, forbidden)
        assertEquals(Detection.Done, out.detection)
        assertEquals(KeyAccess.Unknown, out.access)
        assertEquals(ApiVersion(4, 37, 11), out.apiVersion)
        assertTrue(KeyAction.entries.all { out.toCapabilities(null).access(it) == Access.Unknown })
    }

    @Test fun otherErrorOnMe_failsOpen() = runTest {
        val (out, _) = probe(versionOk, resolverError)
        assertEquals(Detection.Done, out.detection)
        assertEquals(KeyAccess.Unknown, out.access)
    }

    // WHY: a wrong key must be reported as rejected, and only because the server said so.
    @Test fun unauthenticated_isKeyRejected_andSkipsTheSecondQuery() = runTest {
        val (out, engine) = probe(unauthenticated, viewerMe)
        assertEquals(Detection.KeyRejected, out.detection)
        assertEquals(KeyAccess.Unknown, out.access)
        assertEquals(listOf("GetApiVersion"), engine.operations)
    }

    @Test fun unauthenticatedOnMe_isKeyRejected() = runTest {
        val (out, _) = probe(versionOk, unauthenticated)
        assertEquals(Detection.KeyRejected, out.detection)
    }

    // WHY: #247 — an unknown field is HTTP 400 with a validation error; it means an API older than 4.15.0.
    @Test fun versionValidationError_meansTheApiIsTooOld() = runTest {
        val (out, _) = probe(validation400, validation400)
        assertEquals(Detection.Done, out.detection)
        assertTrue(out.versionFieldMissing)
        val caps = out.toCapabilities(null)
        assertTrue(caps.apiTooOld)
        assertEquals(Support.No, caps.supports(ApiFeature.DockerRestart))
        assertEquals(KeyAccess.Unknown, out.access)
    }

    // WHY: a role-less key without INFO is FORBIDDEN on the version; we still learn what we can from `me`.
    @Test fun forbiddenVersion_isUnknown_notTooOld() = runTest {
        val (out, engine) = probe(forbidden, viewerMe)
        assertEquals(Detection.Done, out.detection)
        assertEquals(null, out.apiVersion)
        assertEquals(false, out.versionFieldMissing)
        assertEquals(false, out.toCapabilities(null).apiTooOld)
        assertEquals(KeyAccess.Known(setOf("VIEWER"), emptySet()), out.access)
        assertEquals(listOf("GetApiVersion", "GetKeyAccess"), engine.operations)
    }

    // WHY: an unreachable server is "could not detect", not "key rejected" and not "denied".
    @Test fun transportFailure_isFailed_andHidesNothing() = runTest {
        val (out, engine) = probe(unreachable, unreachable)
        assertEquals(Detection.Failed, out.detection)
        assertEquals(listOf("GetApiVersion"), engine.operations)
        val caps = out.toCapabilities(null)
        assertTrue(KeyAction.entries.all { caps.access(it) == Access.Unknown })
        assertEquals(false, caps.apiTooOld)
    }

    @Test fun serverErrorOnVersion_isFailed() = runTest {
        assertEquals(Detection.Failed, probe(resolverError, viewerMe).first.detection)
    }

    // WHY: the version is worth keeping when only the second query fails.
    @Test fun transportFailureOnMe_keepsTheVersion() = runTest {
        val (out, _) = probe(versionOk, unreachable)
        assertEquals(Detection.Done, out.detection)
        assertEquals(ApiVersion(4, 37, 11), out.apiVersion)
        assertEquals(KeyAccess.Unknown, out.access)
    }

    // WHY: a body that is not GraphQL (a proxy, a captive portal) must not be taken for a verdict.
    @Test fun nonGraphqlHttpError_isFailed() = runTest {
        val (out, _) = probe(reply("<html>nope</html>", status = 502, type = "text/html"), viewerMe)
        assertEquals(Detection.Failed, out.detection)
    }

    @Test fun classifyErrors_firstMatchWins() {
        fun err(code: String?, msg: String = "x") = com.apollographql.apollo.api.Error.Builder(msg)
            .apply { if (code != null) extensions(mapOf("code" to code)) }.build()
        assertEquals(ErrorClass.KeyRejected, classifyErrors(listOf(err("FORBIDDEN"), err("UNAUTHENTICATED"))))
        assertEquals(ErrorClass.ValidationFailed, classifyErrors(listOf(err(null, "Cannot query field \"a\" on type \"B\"."))))
        assertEquals(ErrorClass.ValidationFailed, classifyErrors(listOf(err("GRAPHQL_VALIDATION_FAILED"))))
        assertEquals(ErrorClass.Forbidden, classifyErrors(listOf(err("FORBIDDEN"))))
        assertEquals(ErrorClass.Other, classifyErrors(listOf(err("INTERNAL_SERVER_ERROR"))))
    }
}
