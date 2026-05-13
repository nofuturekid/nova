package net.unraidcontrol.app.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Build a per-server client. URL must already be the base address (no trailing /graphql). */
    fun build(baseUrl: String, apiKey: String): ApolloClient {
        val endpoint = baseUrl.trimEnd('/') + "/graphql"
        val keyed = baseHttpClient.newBuilder()
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
