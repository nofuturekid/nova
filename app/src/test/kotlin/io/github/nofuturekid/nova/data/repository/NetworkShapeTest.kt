package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.ShapeProbe
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.classifyShapeProbe
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.resolveNetworkSupport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkShapeTest {
    private fun err(msg: String, code: String? = null) =
        Error.Builder(msg).apply { if (code != null) extensions(mapOf("code" to code)) }.build()

    // WHY: a validation error for an unknown field is the ONLY signal that a server
    // (API < 4.35) lacks metrics.network. Unraid answers it with HTTP 400, which Apollo
    // may surface as errors OR as an HTTP exception; both must read as "unsupported".
    @Test fun unknown_field_error_is_schema_mismatch() {
        val e = err("Cannot query field \"network\" on type \"Metrics\".", "GRAPHQL_VALIDATION_FAILED")
        assertEquals(ShapeProbe.SchemaMismatch, classifyShapeProbe(null, listOf(e), hasData = false))
    }

    @Test fun http_400_is_schema_mismatch() {
        val ex = ApolloHttpException(400, emptyList(), null, "HTTP error 400", null)
        assertEquals(ShapeProbe.SchemaMismatch, classifyShapeProbe(ex, emptyList(), hasData = false))
    }

    // WHY: an offline server or a bad key says nothing about the schema; reading it as
    // a mismatch would pin the server to "unsupported" and hide throughput for good.
    @Test fun offline_and_auth_failures_are_inconclusive() {
        assertEquals(
            ShapeProbe.Inconclusive,
            classifyShapeProbe(ApolloNetworkException("down"), emptyList(), hasData = false),
        )
        assertEquals(
            ShapeProbe.Inconclusive,
            classifyShapeProbe(null, listOf(err("API key validation failed", "UNAUTHENTICATED")), hasData = false),
        )
    }

    @Test fun clean_data_is_supported() {
        assertEquals(ShapeProbe.Supported, classifyShapeProbe(null, emptyList(), hasData = true))
    }

    // WHY: an unsupporting server must resolve to `false` (quietly absent), never an
    // error state that would surface a banner.
    @Test fun mismatch_resolves_to_unsupported_without_error() = runTest {
        assertEquals(false, resolveNetworkSupport(mutableMapOf(), "s") { ShapeProbe.SchemaMismatch })
        assertEquals(true, resolveNetworkSupport(mutableMapOf(), "s") { ShapeProbe.Supported })
    }

    // WHY: the probe must run once per server, not on every poll/collect, and an
    // inconclusive answer must not be cached (the next attempt re-probes).
    @Test fun definitive_answers_are_cached_inconclusive_are_not() = runTest {
        val cache = mutableMapOf<String, Boolean>()
        var calls = 0
        assertNull(resolveNetworkSupport(cache, "s") { calls++; ShapeProbe.Inconclusive })
        assertEquals(false, resolveNetworkSupport(cache, "s") { calls++; ShapeProbe.SchemaMismatch })
        assertEquals(false, resolveNetworkSupport(cache, "s") { calls++; ShapeProbe.Supported })
        assertEquals(2, calls)
    }
}
