package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.ShapeProbe
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.classifyShapeProbe
import io.github.nofuturekid.nova.data.repository.UnraidRepository.Companion.networkSupportGated
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkShapeTest {
    private fun err(msg: String, code: String? = null) =
        Error.Builder(msg).apply { if (code != null) extensions(mapOf("code" to code)) }.build()

    // WHY: a validation error for an unknown field is the ONLY signal that a server
    // (API < 4.35) lacks metrics.network. Apollo 5 parses Unraid's HTTP 400
    // graphql-response+json body into `errors`, so that is the one mismatch path.
    @Test fun unknown_field_error_is_schema_mismatch() {
        val e = err("Cannot query field \"network\" on type \"Metrics\".", "GRAPHQL_VALIDATION_FAILED")
        assertEquals(ShapeProbe.SchemaMismatch, classifyShapeProbe(null, listOf(e), hasData = false))
    }

    // WHY: a bare HTTP 400 can come from a reverse proxy, edge or captive portal and
    // says nothing about the schema; it must not mark the server unsupported.
    @Test fun non_graphql_http_400_is_inconclusive() {
        val ex = ApolloHttpException(400, emptyList(), null, "HTTP error 400", null)
        assertEquals(ShapeProbe.Inconclusive, classifyShapeProbe(ex, emptyList(), hasData = false))
    }

    // WHY: an offline server or a bad key says nothing about the schema; reading it as
    // a mismatch would hide throughput on a server that supports it.
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

    private fun content(v: Double): () -> Flow<DomainState<Double>> = { flowOf(DomainState.Content(v, "u")) }

    // WHY: an unsupporting server (API < 4.35) must show no throughput and NO error
    // state; the stream must emit nothing at all and finish, not surface an Error.
    @Test fun unsupported_server_emits_nothing_no_error() = runTest {
        val out = networkSupportGated(mutableSetOf(), "s", 1_000L, { ShapeProbe.SchemaMismatch }, content(1.0)).toList()
        assertEquals(emptyList<DomainState<Double>>(), out)
    }

    @Test fun supported_server_runs_the_content_stream() = runTest {
        val out = networkSupportGated(mutableSetOf(), "s", 1_000L, { ShapeProbe.Supported }, content(7.0)).toList()
        assertEquals(listOf<DomainState<Double>>(DomainState.Content(7.0, "u")), out)
    }

    // WHY: offline/auth at start must not decide anything: the probe is retried (with
    // a delay, no tight loop) until it gives a definitive answer.
    @Test fun inconclusive_probe_is_retried_after_the_delay() = runTest {
        var calls = 0
        val out = networkSupportGated(
            mutableSetOf(), "s", 15_000L,
            { if (++calls < 3) ShapeProbe.Inconclusive else ShapeProbe.Supported },
            content(2.0),
        ).toList()
        assertEquals(3, calls)
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(listOf<DomainState<Double>>(DomainState.Content(2.0, "u")), out)
    }

    // WHY: "unsupported" must not stick: after the user upgrades the Unraid API the next
    // Overview start has to probe again, so a `false` is re-probed per collection.
    @Test fun unsupported_is_reprobed_on_every_new_collection() = runTest {
        val supported = mutableSetOf<String>()
        var calls = 0
        val probe: suspend () -> ShapeProbe = { calls++; ShapeProbe.SchemaMismatch }
        networkSupportGated(supported, "s", 1_000L, probe, content(1.0)).toList()
        networkSupportGated(supported, "s", 1_000L, probe, content(1.0)).toList()
        assertEquals(2, calls)
    }

    // WHY: a supporting server is probed once per process, not on every Overview start
    // or poll; the saved query must not be paid again.
    @Test fun supported_is_cached_across_collections() = runTest {
        val supported = mutableSetOf<String>()
        var calls = 0
        val probe: suspend () -> ShapeProbe = { calls++; ShapeProbe.Supported }
        networkSupportGated(supported, "s", 1_000L, probe, content(1.0)).toList()
        networkSupportGated(supported, "s", 1_000L, probe, content(1.0)).toList()
        assertEquals(1, calls)
    }
}
