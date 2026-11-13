package io.github.nofuturekid.nova.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.ApolloHttpException
import com.sun.net.httpserver.HttpServer
import io.github.nofuturekid.nova.data.repository.ErrorReason
import io.github.nofuturekid.nova.data.repository.toQueryError
import io.github.nofuturekid.nova.graphql.DockerContainerStatsSubscription
import io.github.nofuturekid.nova.graphql.GetArrayQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * WHY (ADR-0051): every client that carries the API key must keep it on the address the user
 * entered. OkHttp follows redirects by default and strips only `Authorization`, so a 3xx answer
 * (from a server, or a proxy or hijacked DNS in front of it) used to move the `x-api-key` header to
 * whatever `Location` named: another host, or plain http. The WebSocket client is the same case in
 * a worse form: after a followed handshake the key goes out in `connection_init` to the new host.
 * Unraid itself answers http requests with `302` when SSL is Yes or Strict, so the redirect must
 * also arrive as a reason the user can act on, not as a silent failure.
 */
class KeyedClientRedirectTest {
    private val servers = mutableListOf<HttpServer>()
    private val wsPeers = mutableListOf<WsPeer>()

    @After fun stop() {
        servers.forEach { it.stop(0) }
        wsPeers.forEach { it.close() }
    }

    /** Records the x-api-key header of every request it gets; answers with [status] and [location]. */
    private class Peer(val server: HttpServer, val seenKeys: CopyOnWriteArrayList<String?>) {
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private fun peer(status: Int, location: String? = null): Peer {
        val seen = CopyOnWriteArrayList<String?>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/graphql") { exchange ->
            seen += exchange.requestHeaders.getFirst("x-api-key") ?: "(none)"
            exchange.requestBody.readBytes()
            location?.let { exchange.responseHeaders.add("Location", it) }
            val body = if (status == 200) """{"data":null}""".toByteArray() else ByteArray(0)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        servers += http
        return Peer(http, seen)
    }

    /**
     * A minimal WebSocket server: accepts the upgrade and records the text frames the client sends
     * (the `graphql-ws` `connection_init` carries the API key). It answers nothing else.
     */
    private class WsPeer : AutoCloseable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        val connections = AtomicInteger()
        val frames = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                try {
                    while (true) {
                        val client = socket.accept()
                        connections.incrementAndGet()
                        thread(isDaemon = true) { serve(client) }
                    }
                } catch (_: IOException) { /* closed */ }
            }
        }

        private fun serve(client: Socket) = try {
            client.use {
                it.soTimeout = 5_000
                val input = it.getInputStream().buffered()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return@use
                    head.append(b.toChar())
                }
                val key = Regex("(?im)^Sec-WebSocket-Key: (.+)$").find(head)?.groupValues?.get(1)?.trim() ?: return@use
                val protocol = Regex("(?im)^Sec-WebSocket-Protocol: (.+)$").find(head)?.groupValues?.get(1)?.trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                val reply = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n" +
                    (protocol?.let { p -> "Sec-WebSocket-Protocol: ${p.substringBefore(',').trim()}\r\n" } ?: "") + "\r\n"
                it.getOutputStream().apply { write(reply.toByteArray()); flush() }
                while (true) {
                    val b0 = input.read()
                    if (b0 < 0) break
                    var len = input.read() and 0x7f
                    if (len == 126) len = (input.read() shl 8) or input.read()
                    val mask = ByteArray(4).also { m -> input.readNBytes(m, 0, 4) }
                    val payload = ByteArray(len).also { p -> input.readNBytes(p, 0, len) }
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    if (b0 and 0x0f == 1) frames += String(payload)
                }
            }
        } catch (_: IOException) { /* peer went away or timed out */ }

        override fun close() = socket.close()
    }

    private val variants: Map<String, (ApolloClientFactory, String) -> ApolloClient> = mapOf(
        "build" to { factory, url -> factory.build(url, "secret") },
        "buildLongRunning" to { factory, url -> factory.buildLongRunning(url, "secret") },
    )

    private fun failure(client: ApolloClient) = runBlocking { client.query(GetArrayQuery()).execute().exception }

    @Test fun the_key_never_reaches_a_second_host_through_a_redirect() {
        // 302 is what Unraid's nginx sends; 303/307/308 are the other statuses OkHttp would follow.
        for ((name, build) in variants) for (status in listOf(301, 302, 303, 307, 308)) {
            val elsewhere = peer(200)
            val redirecting = peer(status, location = "${elsewhere.url}/graphql")

            val failure = failure(build(ApolloClientFactory(), redirecting.url))

            assertEquals("$name $status: the address the user entered was asked", listOf("secret"), redirecting.seenKeys.toList())
            assertEquals("$name $status: nothing reached the redirect target", emptyList<String?>(), elsewhere.seenKeys.toList())
            assertTrue("$name $status: a 3xx is a failure, got $failure", failure is ApolloHttpException && failure.statusCode == status)
        }
    }

    // WHY: a redirect that is not followed is the answer the tab gets; it must reach the user as its own
    // reason that names the target. It is no outage: the pill must not say Offline (isConnectionProblem).
    @Test fun a_redirect_reaches_the_user_as_its_own_reason_with_the_target() {
        val elsewhere = peer(200)
        val redirecting = peer(302, location = "${elsewhere.url}/graphql")

        val error = failure(ApolloClientFactory().build(redirecting.url, "secret"))!!.toQueryError("Network error")

        assertEquals(ErrorReason.Redirected, error.reason)
        assertTrue(error.message, error.message.contains("The server redirects to ${elsewhere.url}."))
        assertFalse(error.reason.isConnectionProblem)
    }

    // WHY: nothing may change for the many servers that do not redirect.
    @Test fun a_server_that_does_not_redirect_is_used_as_before() {
        for ((name, build) in variants) {
            val plain = peer(200)

            val failure = failure(build(ApolloClientFactory(), plain.url))

            assertEquals(name, listOf("secret"), plain.seenKeys.toList())
            assertFalse("$name: an answer is no failure of the transport, got $failure", failure is ApolloHttpException)
        }
    }

    // WHY: the downgrade from https to http cannot be staged without a TLS peer; the two switches that
    // forbid it (followRedirects covers every redirect, followSslRedirects the scheme change) are pinned.
    @Test fun the_clients_are_configured_to_follow_no_redirect_at_all() {
        val factory = ApolloClientFactory()
        for ((name, client) in listOf("short" to factory.baseHttpClient, "long" to factory.longRunningHttpClient, "background" to factory.backgroundHttpClient)) {
            assertFalse("$name followRedirects", client.followRedirects)
            assertFalse("$name followSslRedirects", client.followSslRedirects)
        }
    }

    // WHY: the WebSocket sends the key in `connection_init` AFTER the handshake. A followed handshake
    // redirect would complete on the second host and hand it the key; not following ends it at the 302.
    @Test fun the_websocket_handshake_does_not_follow_a_redirect() {
        val elsewhere = WsPeer().also { wsPeers += it }
        val redirecting = peer(302, location = "http://127.0.0.1:${elsewhere.port}/graphql")

        val client = ApolloClientFactory().buildWs(redirecting.url, "secret")
        runBlocking {
            withTimeoutOrNull(4_000) {
                try { client.subscription(DockerContainerStatsSubscription()).toFlow().first() } catch (_: Exception) { null }
            }
        }

        assertTrue("the address the user entered was asked", redirecting.seenKeys.isNotEmpty())
        assertEquals("no connection to the redirect target", 0, elsewhere.connections.get())
        assertEquals("the key was not sent there", emptyList<String>(), elsewhere.frames.toList())
    }
}
