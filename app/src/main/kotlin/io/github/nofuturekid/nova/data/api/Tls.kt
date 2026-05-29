package io.github.nofuturekid.nova.data.api

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Outcome of evaluating a presented leaf certificate against an optional pin. */
sealed interface TrustDecision {
    data object Accept : TrustDecision
    data class FirstUse(val sha256: String) : TrustDecision
    data class Changed(val pinned: String, val presented: String) : TrustDecision
}

/**
 * Pure TOFU decision. [defaultTrusts] = whether the platform default trust
 * manager already accepts the chain (CA-valid). [pinned] = stored SHA-256 or
 * null. [presented] = the leaf's SHA-256. Matching is case-insensitive.
 */
fun decidePinnedTrust(defaultTrusts: Boolean, pinned: String?, presented: String): TrustDecision =
    when {
        defaultTrusts -> TrustDecision.Accept
        pinned == null -> TrustDecision.FirstUse(presented)
        pinned.equals(presented, ignoreCase = true) -> TrustDecision.Accept
        else -> TrustDecision.Changed(pinned, presented)
    }

/** Colon-separated uppercase hex SHA-256 of the certificate's DER encoding. */
fun sha256Hex(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString(":") { "%02X".format(it) }

/** Thrown on first-use of an untrusted cert (live path) so the UI can prompt. */
class CertificateUntrustedException(val sha256: String) :
    CertificateException("Self-signed certificate not yet trusted: $sha256")

/** Thrown when a pinned cert's fingerprint no longer matches. */
class CertificateChangedException(val pinned: String, val presented: String) :
    CertificateException("Pinned certificate changed")

/** How a given Apollo client should evaluate server trust. */
sealed interface TlsTrust {
    /** Stock platform validation (system CAs). */
    data object Default : TlsTrust
    /**
     * Self-signed-aware path for ONE server's local https endpoint.
     * [acceptFirstUse] = true only for the pre-save connectivity test
     * (accept-and-report, no persistence); false for the live data path
     * (throw on first use so the UI prompts and then pins).
     */
    data class PinnedSelfSigned(val pinnedSha256: String?, val acceptFirstUse: Boolean = false) : TlsTrust
}

/** The platform's default X509 trust manager (system CA store). */
fun systemDefaultTrustManager(): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
}

/**
 * Wraps the platform default. CA-valid chains pass straight through; otherwise
 * the pin decides (see [decidePinnedTrust]). On the live path a first-use or
 * changed cert throws a typed exception that propagates up through Apollo.
 */
class LocalPinningTrustManager(
    private val default: X509TrustManager,
    private val pinnedSha256: String?,
    private val acceptFirstUse: Boolean,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val defaultTrusts = runCatching { default.checkServerTrusted(chain, authType) }.isSuccess
        val presented = sha256Hex(chain[0])
        when (val d = decidePinnedTrust(defaultTrusts, pinnedSha256, presented)) {
            is TrustDecision.Accept -> return
            is TrustDecision.FirstUse -> if (!acceptFirstUse) throw CertificateUntrustedException(d.sha256)
            is TrustDecision.Changed -> throw CertificateChangedException(d.pinned, d.presented)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        default.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = default.acceptedIssuers
}

/** Build an [SSLContext] whose only trust manager is [tm]. */
fun sslContextFor(tm: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }

/**
 * The ONLY place "trust is relaxed" is decided. Pure + top-level so it is
 * unit-testable and the local-only guarantee is provable: relaxed trust is
 * returned ONLY for a Local-mode, opt-in, https endpoint. Everything else —
 * remote mode, http, flag off — gets [TlsTrust.Default]. Pure (no I/O); the
 * pin is passed in.
 */
fun trustFor(
    mode: io.github.nofuturekid.nova.data.model.ConnectionMode,
    trustSelfSignedLocal: Boolean,
    url: String,
    pin: String?,
): TlsTrust =
    if (mode == io.github.nofuturekid.nova.data.model.ConnectionMode.Local &&
        trustSelfSignedLocal &&
        url.startsWith("https", ignoreCase = true)
    ) {
        TlsTrust.PinnedSelfSigned(pin)
    } else {
        TlsTrust.Default
    }

/** A cert-trust problem surfaced from a failed connection, with fingerprints. */
sealed interface CertIssue {
    data class Untrusted(val sha256: String) : CertIssue
    data class Changed(val pinned: String, val presented: String) : CertIssue
}

/** Walk a throwable's cause chain for a typed cert exception. */
fun Throwable.certIssue(): CertIssue? {
    var t: Throwable? = this
    while (t != null) {
        when (t) {
            is CertificateUntrustedException -> return CertIssue.Untrusted(t.sha256)
            is CertificateChangedException -> return CertIssue.Changed(t.pinned, t.presented)
        }
        t = t.cause
    }
    return null
}
