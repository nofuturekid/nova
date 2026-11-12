package io.github.nofuturekid.nova.data.repository

import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import io.github.nofuturekid.nova.data.api.CertIssue
import io.github.nofuturekid.nova.data.api.CertificateChangedException
import io.github.nofuturekid.nova.data.api.CertificateUntrustedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ResponseClassifierTest {

    // WHY: Apollo Kotlin 5 returns TLS failures in response.exception (it no
    // longer throws). A self-signed cert must be recognised as a Cert case —
    // even when our typed exception is nested under the platform exception —
    // so the prompt can be surfaced. This is the exact bug that shipped in beta1.
    @Test fun exception_withUntrustedCertInCauseChain_isCert() {
        val ex = ApolloNetworkException(
            message = "TLS failed",
            platformCause = IOException("handshake", CertificateUntrustedException("AA:BB")),
        )
        assertEquals(
            RespClass.Cert(CertIssue.Untrusted("AA:BB")),
            classifyResponse(ex, hasErrors = false, errorMessage = null, dataIsNull = true),
        )
    }

    @Test fun exception_withChangedCert_isCert() {
        val ex = ApolloNetworkException("TLS", platformCause = CertificateChangedException("OLD", "NEW"))
        assertEquals(
            RespClass.Cert(CertIssue.Changed("OLD", "NEW")),
            classifyResponse(ex, hasErrors = false, errorMessage = null, dataIsNull = true),
        )
    }

    // WHY: a non-cert network error must surface as a real failure message,
    // not be masked as "Empty GraphQL response" (the beta1 misclassification).
    @Test fun exception_nonCert_isFailedWithMessage() {
        val ex = ApolloNetworkException("Unable to resolve host")
        assertEquals(
            RespClass.Failed("Unable to resolve host"),
            classifyResponse(ex, hasErrors = false, errorMessage = null, dataIsNull = true),
        )
    }

    // WHY: Apollo wraps every parsing failure as "Error while reading JSON
    // response" and keeps the reason only in the cause. #217 could not be
    // diagnosed because the app showed just that wrapper text.
    @Test fun exception_withCause_showsRootCause() {
        val ex = ApolloNetworkException(
            message = "Error while reading JSON response",
            platformCause = IllegalStateException("wrapped", java.io.EOFException("End of input at path $.data")),
        )
        assertEquals(
            RespClass.Failed("Error while reading JSON response (EOFException: End of input at path $.data)"),
            classifyResponse(ex, hasErrors = false, errorMessage = null, dataIsNull = true),
        )
    }

    // WHY: a cause without a message still names its type, so the report says
    // what failed even then.
    @Test fun failureMessage_causeWithoutMessage_namesType() {
        val ex = ApolloNetworkException("Error while reading JSON response", platformCause = NullPointerException())
        assertEquals(
            "Error while reading JSON response (NullPointerException)",
            ex.failureMessage("Network error"),
        )
    }

    // WHY: a long cause (e.g. a whole HTML page) must not flood the error card.
    @Test fun failureMessage_longCause_isCapped() {
        val ex = ApolloNetworkException("Error while reading JSON response", platformCause = IllegalStateException("x".repeat(500)))
        assertEquals(
            "Error while reading JSON response (IllegalStateException: ${"x".repeat(200)})",
            ex.failureMessage("Network error"),
        )
    }

    // WHY: GraphQL-body errors (e.g. bad query) are a failure, distinct from transport.
    @Test fun graphqlErrors_isFailed() {
        assertEquals(
            RespClass.Failed("bad field"),
            classifyResponse(exception = null, hasErrors = true, errorMessage = "bad field", dataIsNull = false),
        )
    }

    // WHY: no exception, no errors, but null data → genuinely empty response.
    @Test fun noExceptionNullData_isEmpty() {
        assertEquals(RespClass.Empty, classifyResponse(null, hasErrors = false, errorMessage = null, dataIsNull = true))
    }

    @Test fun data_isOk() {
        assertEquals(RespClass.Ok, classifyResponse(null, hasErrors = false, errorMessage = null, dataIsNull = false))
    }

    // Same simple name as android.system.GaiException, which is not on the JVM test classpath.
    private class GaiException(message: String) : RuntimeException(message)

    // WHY (#266): with no DNS answer the tab showed the raw exception text
    // ("Failed to execute GraphQL http network request (GaiException: ...)").
    // The user must read a plain sentence; the raw text stays in `detail` for
    // diagnosis. If the mapping regresses, `message` is the technical text again.
    @Test fun unreachableServer_getsPlainSentenceAndKeepsRawDetail() {
        val ex = ApolloNetworkException(
            message = "Failed to execute GraphQL http network request",
            platformCause = GaiException("android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)"),
        )
        val err = ex.toQueryError("Network error")
        assertEquals(UNREACHABLE_MESSAGE, err.message)
        assertEquals(
            "Failed to execute GraphQL http network request " +
                "(GaiException: android_getaddrinfo failed: EAI_NODATA (No address associated with hostname))",
            err.detail,
        )
    }

    @Test fun refusedConnectionAndTimeout_areUnreachableToo() {
        assertEquals(
            UNREACHABLE_MESSAGE,
            ApolloNetworkException("x", platformCause = java.net.ConnectException("refused")).toQueryError("n").message,
        )
        assertEquals(
            UNREACHABLE_MESSAGE,
            ApolloNetworkException("x", platformCause = java.net.SocketTimeoutException("timeout")).toQueryError("n").message,
        )
    }

    // WHY (#266 acceptance): certificate problems keep their honest text, even
    // when a socket error sits next to them in the cause chain.
    @Test fun tlsFailure_keepsItsSpecificMessage() {
        val ex = ApolloNetworkException(
            message = "TLS handshake failed",
            platformCause = javax.net.ssl.SSLHandshakeException("PKIX path building failed"),
        )
        val err = ex.toQueryError("Network error")
        assertEquals("TLS handshake failed (SSLHandshakeException: PKIX path building failed)", err.message)
        assertNull(err.detail)
    }

    // WHY (#266 acceptance): an HTTP status answer proves the server was reached;
    // "check the address" would be wrong, the status text is the diagnosis.
    @Test fun httpStatusError_keepsItsSpecificMessage() {
        val err = ApolloHttpException(401, emptyList(), null, "HTTP error 401", null).toQueryError("Network error")
        assertEquals("HTTP error 401", err.message)
        assertNull(err.detail)
    }

    // WHY: #217 — a body that is not JSON arrives as a network exception whose
    // cause is the reason; it must not be hidden behind the unreachable sentence.
    @Test fun unparsableResponse_keepsItsRootCause() {
        val ex = ApolloNetworkException(
            message = "Error while reading JSON response",
            platformCause = java.io.EOFException("End of input at path $.data"),
        )
        val err = ex.toQueryError("Network error")
        assertEquals("Error while reading JSON response (EOFException: End of input at path $.data)", err.message)
        assertNull(err.detail)
    }
}
