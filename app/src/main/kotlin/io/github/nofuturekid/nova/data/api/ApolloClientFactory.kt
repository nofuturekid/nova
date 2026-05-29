package io.github.nofuturekid.nova.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.apollographql.apollo.network.ws.DefaultWebSocketEngine
import com.apollographql.apollo.network.ws.GraphQLWsProtocol
import com.apollographql.apollo.network.ws.WebSocketNetworkTransport
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds per-server [ApolloClient] instances. Three variants:
 *
 * - [build] — short timeouts (8 s connect, 15 s read/write). Used for
 *   polling queries and short-lived mutations (start/stop/pause/etc.).
 *   The 15-second read timeout is deliberately short so the snapshot
 *   polling loop can quickly distinguish a healthy server from an
 *   unreachable one.
 *
 * - [buildLongRunning] — short connect (8 s) but long read/write (10 min).
 *   Used for the few mutations that involve server-side image pull +
 *   container recreate, which can legitimately take several minutes.
 *   Per ADR-0016 these get their own client so the polling timeout
 *   doesn't have to be relaxed globally.
 *
 * - [buildWs] — WebSocket-only client for GraphQL subscriptions. Reuses
 *   the existing self-signed TLS pin (ADR-0041) via [applyTrust]. Uses
 *   `graphql-ws` protocol with `x-api-key` in the connection payload.
 *   See ADR-0042. Lifecycle ownership is identical to the HTTP variants:
 *   the factory owns these; callers MUST NOT close them (ADR-0028).
 */
@Singleton
class ApolloClientFactory @Inject constructor() {

    private val baseHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (io.github.nofuturekid.nova.BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    private val longRunningHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (io.github.nofuturekid.nova.BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .addInterceptor(logging)
            .build()
    }

    /**
     * One [ApolloClient] per (variant, endpoint, apiKey). Building a fresh
     * client on every call (each poll iteration, mutation, refresh, ping)
     * leaked an ApolloClient + its wrapping OkHttpClient every time. Clients
     * are reused for the app's lifetime and bounded by the number of distinct
     * server configs (typically 1–2 servers × 2 variants). Callers MUST NOT
     * close these — the factory owns their lifecycle. See ADR-0028 (and
     * ADR-0016, which already assumed this per-server cache existed).
     */
    private val clients = ConcurrentHashMap<String, ApolloClient>()

    /** WS subscription clients, keyed on (endpoint, apiKey, trust). Same
     *  lifecycle ownership as [clients] — factory owns, callers don't close. */
    private val wsClients = ConcurrentHashMap<String, ApolloClient>()

    /** Build a per-server client. URL must already be the base address (no trailing /graphql). */
    fun build(baseUrl: String, apiKey: String, trust: TlsTrust = TlsTrust.Default): ApolloClient =
        cached("short", baseHttpClient, baseUrl, apiKey, trust)

    /** Same as [build] but uses the long-running OkHttp client (10-min
     *  read/write). Use exclusively for mutations that pull images or
     *  do other multi-minute server-side work. */
    fun buildLongRunning(baseUrl: String, apiKey: String, trust: TlsTrust = TlsTrust.Default): ApolloClient =
        cached("long", longRunningHttpClient, baseUrl, apiKey, trust)

    /**
     * Build a WebSocket-backed [ApolloClient] for GraphQL subscriptions.
     *
     * The HTTP endpoint ([baseUrl]/graphql) is set as `serverUrl` so that
     * `apolloClient.query(...)` would still work; in practice only
     * `apolloClient.subscription(...)` is called on WS clients. The WS URL
     * is derived by replacing the scheme (`https→wss`, `http→ws`).
     *
     * The OkHttpClient used for the WS engine is built with [applyTrust] so
     * the existing self-signed-cert pin (ADR-0041) is honoured for local
     * `wss://` endpoints — same trust path as the HTTP polling client.
     *
     * Authentication is sent in the `graphql-ws` connection-init payload
     * (`x-api-key`), matching the server's auth guard that reads connection
     * params as headers. See ADR-0042.
     */
    fun buildWs(baseUrl: String, apiKey: String, trust: TlsTrust = TlsTrust.Default): ApolloClient =
        wsClients.computeIfAbsent(wsKey(baseUrl, apiKey, trust)) {
            val httpEndpoint = baseUrl.trimEnd('/') + "/graphql"
            val wsEndpoint = httpEndpoint.replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
            val wsOkHttp = baseHttpClient.newBuilder().applyTrust(trust).build()
            ApolloClient.Builder()
                .serverUrl(httpEndpoint)
                .subscriptionNetworkTransport(
                    WebSocketNetworkTransport.Builder()
                        .serverUrl(wsEndpoint)
                        .protocol(GraphQLWsProtocol.Factory(
                            connectionPayload = { mapOf("x-api-key" to apiKey) }
                        ))
                        .webSocketEngine(DefaultWebSocketEngine(wsOkHttp))
                        .build()
                )
                .build()
        }

    private fun wsKey(baseUrl: String, apiKey: String, trust: TlsTrust): String {
        val trustKey = trustKey(trust)
        val endpoint = baseUrl.trimEnd('/') + "/graphql"
        return "ws|$endpoint|$apiKey|$trustKey"
    }

    private fun cached(
        variant: String,
        base: OkHttpClient,
        baseUrl: String,
        apiKey: String,
        trust: TlsTrust,
    ): ApolloClient {
        val endpoint = baseUrl.trimEnd('/') + "/graphql"
        // Trust is part of the key: a Default and a PinnedSelfSigned client for the
        // same endpoint must not collide, and changing the pin must yield a fresh
        // client (so the new fingerprint snapshot takes effect). Old entries are
        // bounded and harmless (ADR-0028 lifecycle ownership).
        return clients.computeIfAbsent("$variant|$endpoint|$apiKey|${trustKey(trust)}") {
            buildOn(base, endpoint, apiKey, trust)
        }
    }

    private fun trustKey(trust: TlsTrust): String = when (trust) {
        TlsTrust.Default -> "d"
        is TlsTrust.PinnedSelfSigned -> "p:${trust.pinnedSha256 ?: "none"}:${trust.acceptFirstUse}"
    }

    private fun buildOn(base: OkHttpClient, endpoint: String, apiKey: String, trust: TlsTrust): ApolloClient {
        val keyed = base.newBuilder()
            .applyTrust(trust)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("x-api-key", apiKey)
                    .addHeader("Accept", "application/json")
                    .build()
                chain.proceed(req)
            }
            .build()
        return ApolloClient.Builder()
            .serverUrl(endpoint)
            .okHttpClient(keyed)
            .build()
    }

    /**
     * Apply TLS trust configuration to an [OkHttpClient.Builder].
     *
     * For [TlsTrust.PinnedSelfSigned] endpoints (ADR-0041), installs the
     * [LocalPinningTrustManager] and bypasses hostname verification (self-signed
     * LAN certs rarely match the host). For [TlsTrust.Default] this is a no-op.
     * Shared between the HTTP ([buildOn]) and WS ([buildWs]) paths so the exact
     * same pin logic applies to both transports — no drift possible.
     */
    private fun OkHttpClient.Builder.applyTrust(trust: TlsTrust): OkHttpClient.Builder = apply {
        if (trust is TlsTrust.PinnedSelfSigned) {
            val tm = LocalPinningTrustManager(
                default = systemDefaultTrustManager(),
                pinnedSha256 = trust.pinnedSha256,
                acceptFirstUse = trust.acceptFirstUse,
            )
            sslSocketFactory(sslContextFor(tm).socketFactory, tm)
            // Pin is the anchor; this client only ever talks to one server's
            // local endpoint (see ADR-0041). Self-signed LAN certs rarely
            // match the host, so hostname matching is intentionally bypassed.
            hostnameVerifier { _, _ -> true }
        }
    }
}
