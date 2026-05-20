package io.github.nofuturekid.nova.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.ConcurrentHashMap
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

    /** Build a per-server client. URL must already be the base address (no trailing /graphql). */
    fun build(baseUrl: String, apiKey: String): ApolloClient =
        cached("short", baseHttpClient, baseUrl, apiKey)

    /** Same as [build] but uses the long-running OkHttp client (10-min
     *  read/write). Use exclusively for mutations that pull images or
     *  do other multi-minute server-side work. */
    fun buildLongRunning(baseUrl: String, apiKey: String): ApolloClient =
        cached("long", longRunningHttpClient, baseUrl, apiKey)

    private fun cached(
        variant: String,
        base: OkHttpClient,
        baseUrl: String,
        apiKey: String,
    ): ApolloClient {
        val endpoint = baseUrl.trimEnd('/') + "/graphql"
        // computeIfAbsent is atomic on ConcurrentHashMap — no duplicate build
        // under concurrent poll streams.
        return clients.computeIfAbsent("$variant|$endpoint|$apiKey") {
            buildOn(base, endpoint, apiKey)
        }
    }

    private fun buildOn(base: OkHttpClient, endpoint: String, apiKey: String): ApolloClient {
        val keyed = base.newBuilder()
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
}
