package io.github.nofuturekid.nova.data.alerts

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.sun.net.httpserver.HttpServer
import io.github.nofuturekid.nova.data.api.CertificateChangedException
import io.github.nofuturekid.nova.data.api.TlsTrust
import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.NotifImportance
import io.github.nofuturekid.nova.data.model.Server
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException

/**
 * The real probe against a local HTTP server that speaks canned GraphQL.
 *
 * WHY: the probe is the only code that talks to a server in the background, so what it sends and
 * how it classifies the answers decides what the user is told. These tests would fail if it sent
 * a query the app does not already ship, tried the remote address when the local one answered,
 * took "wrong key" for "server down" (or the reverse), let one broken half hide the other,
 * accepted a certificate, or sent a request when it has no key.
 */
class ApolloServerProbeTest {
    private val httpServers = mutableListOf<HttpServer>()
    private val operations = CopyOnWriteArrayList<String>()
    private val requestedUrls = CopyOnWriteArrayList<String>()
    private val trusts = CopyOnWriteArrayList<TlsTrust>()

    @After fun stop() = httpServers.forEach { it.stop(0) }

    private val notificationsOk = """{"data":{"notifications":{"overview":{"unread":{"info":0,"warning":0,"alert":1,"total":1}},"warningsAndAlerts":[{"id":"n1","title":"disk","subject":"Alert [Tower] - disk1 is hot","description":"52 C","importance":"ALERT","timestamp":"2026-11-13T10:00:00.000Z"}]}}}"""
    private val arrayOk = """{"data":{"array":{"state":"STOPPED","capacity":{"kilobytes":{"total":"8000000000","used":"2000000000","free":"6000000000"}},"parities":[],"disks":[],"caches":[],"parityCheckStatus":null}}}"""
    private val unauthenticated = """{"errors":[{"message":"Unauthorized","extensions":{"code":"UNAUTHENTICATED"}}]}"""

    /** Starts a server; [answer] gets the operation name and returns status and body. */
    private fun serve(answer: (String) -> Pair<Int, String> = { op -> 200 to if (op == "GetNotifications") notificationsOk else arrayOk }): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/graphql") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val op = Regex(""""operationName"\s*:\s*"(\w+)"""").find(body)?.groupValues?.get(1).orEmpty()
            operations += op
            val (status, text) = answer(op)
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        httpServers += http
        return "http://127.0.0.1:${http.address.port}"
    }

    /** A URL nobody listens on. */
    private fun closedUrl(): String = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }

    private fun probe(
        key: ApiKeyResult = ApiKeyResult.Present("k"),
        client: (String, TlsTrust) -> ApolloClient = { url, _ -> plainClient(url) },
    ) = ApolloServerProbe(
        readKey = { key },
        clientFor = { url, _, trust ->
            requestedUrls += url
            trusts += trust
            client(url, trust)
        },
    )

    /** A client whose TLS check throws what the pinning trust manager throws for a changed certificate. */
    private fun certFailingClient() = ApolloClient.Builder().serverUrl("http://127.0.0.1:1/graphql")
        .okHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { throw SSLHandshakeException("changed").apply { initCause(CertificateChangedException("AA", "BB")) } }
                .build(),
        ).build()

    private fun plainClient(url: String) = ApolloClient.Builder().serverUrl(url.trimEnd('/') + "/graphql").build()

    private fun server(local: String, remote: String = "") = Server(
        id = "srv", name = "Tower", hostname = "tower", localUrl = local, remoteUrl = remote,
    )

    private fun run(p: ApolloServerProbe, s: Server, pin: String? = null) = runBlocking { p.probe(s, pin) }

    @Test fun asks_exactly_the_two_shipped_queries_once_each_and_maps_both_answers() {
        val result = run(probe(), server(serve()))

        result as ProbeResult.Ok
        assertEquals(listOf("GetNotifications", "GetArray"), operations.toList())
        assertEquals("n1", result.notifications!!.items.single().id)
        assertEquals(NotifImportance.Alert, result.notifications!!.items.single().importance)
        assertEquals(ArrayState.Stopped, result.array!!.state)
    }

    @Test fun a_local_address_that_answers_means_the_remote_address_is_never_contacted() {
        val remote = serve()
        val local = serve()

        run(probe(), server(local, remote))

        assertEquals(listOf(local), requestedUrls.toList())
        assertEquals(2, operations.size)
    }

    @Test fun the_remote_address_is_tried_when_the_local_one_gives_no_answer_and_trusts_by_default() {
        val remote = serve()

        val result = run(probe(), server(closedUrl(), remote))

        assertTrue(result is ProbeResult.Ok)
        assertEquals("local first, then remote", 2, requestedUrls.size)
        assertEquals(remote, requestedUrls.last())
        assertEquals(TlsTrust.Default, trusts.last())
    }

    @Test fun nothing_answering_is_unreachable_and_not_an_error_of_the_server() {
        assertEquals(ProbeResult.Unreachable, run(probe(), server(closedUrl(), closedUrl())))
    }

    @Test fun a_server_that_refuses_the_key_is_rejected_and_the_other_address_is_not_tried() {
        // WHY: "wrong key" is the user's to fix and must not be mistaken for "server down"; the
        // other address would get the same key and the same answer.
        val local = serve { 200 to unauthenticated }
        val remote = serve()

        val result = run(probe(), server(local, remote))

        assertTrue(result is ProbeResult.Rejected)
        assertEquals(listOf(local), requestedUrls.toList())
    }

    @Test fun an_http_error_status_is_rejected_too() {
        val result = run(probe(), server(serve { 403 to "forbidden" }))

        assertTrue(result is ProbeResult.Rejected)
    }

    @Test fun one_half_failing_keeps_the_other_half() {
        // WHY: an older or stricter server may refuse one query; the other half must still be
        // evaluated, and the evaluation leaves the failed half's memory alone.
        val result = run(probe(), server(serve { op -> 200 to if (op == "GetNotifications") unauthenticated else arrayOk }))

        result as ProbeResult.Ok
        assertNull(result.notifications)
        assertEquals(ArrayState.Stopped, result.array!!.state)
    }

    @Test fun a_certificate_problem_is_reported_and_never_prompts_or_pins() {
        val failing = { _: String, _: TlsTrust -> certFailingClient() }

        val result = run(probe(client = failing), server("https://tower.local"), pin = "AA")

        assertEquals(ProbeResult.CertificateProblem, result)
    }

    @Test fun a_certificate_problem_on_the_local_address_does_not_hide_a_trusted_remote_answer() {
        val remote = serve()
        val client = { url: String, _: TlsTrust ->
            if (url == remote) plainClient(url) else certFailingClient()
        }

        val result = run(probe(client = client), server("https://tower.local", remote))

        assertTrue(result is ProbeResult.Ok)
    }

    @Test fun a_certificate_problem_wins_over_unreachable_when_no_address_answers() {
        val client = { url: String, _: TlsTrust ->
            if (url.startsWith("https")) certFailingClient() else plainClient(url)
        }

        assertEquals(
            ProbeResult.CertificateProblem,
            run(probe(client = client), server("https://tower.local", closedUrl())),
        )
    }

    @Test fun a_missing_or_undecryptable_key_is_reported_without_any_request() {
        val s = server(serve())

        assertEquals(ProbeResult.KeyMissing, run(probe(key = ApiKeyResult.Absent), s))
        assertEquals(ProbeResult.KeyMissing, run(probe(key = ApiKeyResult.Present("  ")), s))
        assertEquals(ProbeResult.KeyUndecryptable, run(probe(key = ApiKeyResult.Undecryptable), s))
        assertEquals("no client was even built", emptyList<String>(), requestedUrls.toList())
        assertEquals(emptyList<String>(), operations.toList())
    }
}
