package io.github.nofuturekid.nova.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.network.okHttpClient
import com.sun.net.httpserver.HttpServer
import io.github.nofuturekid.nova.graphql.GetArrayQuery
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WHY: OkHttp follows redirects by default and strips only `Authorization` on the way, so a 3xx
 * answer would send the `x-api-key` header, and on 307/308 the POST body too, to whatever
 * `Location` says: another host, or http. In the background nobody is looking, so a verified
 * https peer, or a proxy in front of it, must not be able to move the key anywhere. The
 * foreground client is deliberately left as it is; a guard test pins that down.
 */
class BackgroundClientRedirectTest {
    private val servers = mutableListOf<HttpServer>()

    @After fun stop() = servers.forEach { it.stop(0) }

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

    private fun exception(client: ApolloClient) = runBlocking { client.query(GetArrayQuery()).execute().exception }

    /**
     * The background OkHttp client with cleartext allowed again, so a test can talk to a plain
     * http peer and see the redirect behaviour on its own, not masked by the cleartext block.
     */
    private fun backgroundClientAllowingCleartext(factory: ApolloClientFactory, base: String): ApolloClient {
        val okHttp = factory.backgroundHttpClient.newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().addHeader("x-api-key", "secret").build()) }
            .build()
        return ApolloClient.Builder().serverUrl("$base/graphql").okHttpClient(okHttp).build()
    }

    @Test fun the_background_client_does_not_follow_a_redirect_so_the_key_goes_nowhere_else() {
        val elsewhere = peer(200)
        val redirecting = peer(307, location = "${elsewhere.url}/graphql")

        val failure = exception(backgroundClientAllowingCleartext(ApolloClientFactory(), redirecting.url))

        assertEquals("the first server was really asked, so the test is not masked", listOf("secret"), redirecting.seenKeys.toList())
        assertEquals("the key must not follow the redirect", emptyList<String?>(), elsewhere.seenKeys.toList())
        assertTrue("a 3xx answer is a failure, got $failure", failure is ApolloHttpException && failure.statusCode == 307)
    }

    @Test fun the_background_client_does_not_follow_a_redirect_to_another_scheme_either() {
        // WHY: a redirect from https down to http is the cleartext leak; followSslRedirects covers it.
        val elsewhere = peer(200)
        val redirecting = peer(308, location = "${elsewhere.url}/graphql")

        exception(backgroundClientAllowingCleartext(ApolloClientFactory(), redirecting.url))

        assertEquals(emptyList<String?>(), elsewhere.seenKeys.toList())
    }

    @Test fun buildBackground_refuses_cleartext_before_any_request_leaves() {
        val plain = peer(200)

        val failure = exception(ApolloClientFactory().buildBackground(plain.url, "secret", TlsTrust.Default))

        assertNotNull("an http address must fail", failure)
        assertEquals("nothing reached the http server", emptyList<String?>(), plain.seenKeys.toList())
    }

    @Test fun the_foreground_client_still_follows_redirects() {
        // WHY: the foreground behaviour of the app is deliberately unchanged; this guard fails if
        // the redirect and cleartext settings ever leak from the background variant into build().
        val elsewhere = peer(200)
        val redirecting = peer(307, location = "${elsewhere.url}/graphql")

        exception(ApolloClientFactory().build(redirecting.url, "secret"))

        assertEquals(listOf("secret"), redirecting.seenKeys.toList())
        assertEquals("the foreground follows the redirect, as before", listOf("secret"), elsewhere.seenKeys.toList())
    }
}
