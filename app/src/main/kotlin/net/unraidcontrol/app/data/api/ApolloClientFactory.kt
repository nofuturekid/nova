package net.unraidcontrol.app.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.apollographql.apollo.network.websocket.GraphQLWsProtocol
import com.apollographql.apollo.network.websocket.WebSocketNetworkTransport
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds per-server [ApolloClient] instances. Two variants:
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
 */
@Singleton
class ApolloClientFactory @Inject constructor() {

    private val baseHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
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
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .addInterceptor(logging)
            .build()
    }

    /** Build a per-server client. URL must already be the base address (no trailing /graphql). */
    fun build(baseUrl: String, apiKey: String): ApolloClient =
        buildOn(baseHttpClient, baseUrl, apiKey)

    /** Same as [build] but uses the long-running OkHttp client (10-min
     *  read/write). Use exclusively for mutations that pull images or
     *  do other multi-minute server-side work. */
    fun buildLongRunning(baseUrl: String, apiKey: String): ApolloClient =
        buildOn(longRunningHttpClient, baseUrl, apiKey)

    private fun buildOn(base: OkHttpClient, baseUrl: String, apiKey: String): ApolloClient {
        val endpoint = baseUrl.trimEnd('/') + "/graphql"
        val keyed = base.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("x-api-key", apiKey)
                    .addHeader("Accept", "application/json")
                    .build()
                chain.proceed(req)
            }
            .build()
        // Phase E (ADR-0026): subscriptions ride the same vhost/path as
        // HTTP — only the scheme differs (https→wss, http→ws). The WS
        // socket is opened lazily by Apollo on the first collected
        // subscription and closed when none remain, so query/mutation
        // clients carry this config at zero cost until used. Auth reuses
        // the same x-api-key, sent in the graphql-ws connection_init
        // payload (the server merges connectionParams into headers).
        val wsEndpoint = endpoint
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val wsTransport = WebSocketNetworkTransport.Builder()
            .serverUrl(wsEndpoint)
            .wsProtocol(GraphQLWsProtocol(connectionPayload = { mapOf("x-api-key" to apiKey) }))
            .build()
        return ApolloClient.Builder()
            .serverUrl(endpoint)
            .okHttpClient(keyed)
            .subscriptionNetworkTransport(wsTransport)
            .build()
    }
}
