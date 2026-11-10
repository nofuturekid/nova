package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.ConnectionMode
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
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
 *
 * Accept here is only the chain half of the decision: a CA-valid chain that
 * does not match the pin is accepted only together with normal hostname
 * verification, see [hostnameTrusted] / [PinRelaxedHostnameVerifier].
 * On Android, [defaultTrusts] is currently always false: the app's per-domain
 * network security config makes the platform trust manager reject the
 * hostname-unaware check, so a CA-valid local cert goes through the pin prompt.
 * This logic must not depend on that unrelated config, hence the hostname rule.
 */
fun decidePinnedTrust(defaultTrusts: Boolean, pinned: String?, presented: String): TrustDecision =
    when {
        pinned != null && pinned.equals(presented, ignoreCase = true) -> TrustDecision.Accept
        defaultTrusts -> TrustDecision.Accept
        pinned == null -> TrustDecision.FirstUse(presented)
        else -> TrustDecision.Changed(pinned, presented)
    }

/**
 * Pure hostname decision. The pin is the trust anchor (ADR-0041): hostname
 * matching is relaxed ONLY when the presented leaf equals the stored pin.
 * Any other chain (e.g. a CA-valid one) must pass the normal verifier
 * ([defaultVerifies]).
 */
fun hostnameTrusted(pinned: String?, presented: String, defaultVerifies: () -> Boolean): Boolean =
    (pinned != null && pinned.equals(presented, ignoreCase = true)) || defaultVerifies()

/** Hostname verifier for the local pinned client: relaxed only for the pinned leaf. */
class PinRelaxedHostnameVerifier(
    private val pinnedSha256: String?,
    private val default: HostnameVerifier,
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        } ?: return false // no usable peer certificate: fail closed
        return hostnameTrusted(pinnedSha256, sha256Hex(leaf)) { default.verify(hostname, session) }
    }
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
     * [acceptFirstUse] = true would accept-and-report an unpinned cert without
     * persisting; every production caller passes false (throw on first use so
     * the UI prompts and then pins).
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
 * Wraps the platform default. A pin match or a CA-valid chain passes (the
 * latter still has to pass normal hostname verification; on Android the
 * default's hostname-unaware check always throws, see [decidePinnedTrust]); otherwise the pin
 * decides (see [decidePinnedTrust]). On the live path a first-use or
 * changed cert throws a typed exception that propagates up through Apollo.
 */
class LocalPinningTrustManager(
    private val default: X509TrustManager,
    private val pinnedSha256: String?,
    private val acceptFirstUse: Boolean,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
        val defaultTrusts = try {
            default.checkServerTrusted(chain, authType)
            true
        } catch (_: CertificateException) {
            false
        }
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
    mode: ConnectionMode,
    trustSelfSignedLocal: Boolean,
    url: String,
    pin: String?,
): TlsTrust =
    if (mode == ConnectionMode.Local &&
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
