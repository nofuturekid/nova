package io.github.nofuturekid.nova.data.api

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * The hostname check may be relaxed ONLY for the pinned leaf (ADR-0041); every
 * other certificate gets OkHttp's stock verifier. Uses a real (self-signed,
 * SAN attacker.example) certificate and a stub session.
 */
class PinRelaxedHostnameVerifierTest {

    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIBqzCCAVCgAwIBAgIUKGerylCYcIbApnQ7b0tBRtR0+mIwCgYIKoZIzj0EAwIw
        GzEZMBcGA1UEAwwQYXR0YWNrZXIuZXhhbXBsZTAgFw0yNjExMTAyMDQwMThaGA8y
        MTI2MTAxNzIwNDAxOFowGzEZMBcGA1UEAwwQYXR0YWNrZXIuZXhhbXBsZTBZMBMG
        ByqGSM49AgEGCCqGSM49AwEHA0IABDxBq5vXAMf0K74q4QCX2SnCyO8uIeoMJxJq
        85S3RFaZvOSnus7caE+9DMgxNlBJgGXzPng0cDJLDwrhTdWxnQ+jcDBuMB0GA1Ud
        DgQWBBQeSn4mEwVRYnjE1ichINXIE+ZGbDAfBgNVHSMEGDAWgBQeSn4mEwVRYnjE
        1ichINXIE+ZGbDAPBgNVHRMBAf8EBTADAQH/MBsGA1UdEQQUMBKCEGF0dGFja2Vy
        LmV4YW1wbGUwCgYIKoZIzj0EAwIDSQAwRgIhAMQwZOxTKmD1dlPcCazBgs8bwed1
        whrrvTdI1U5TdDW8AiEAkMf8JJN1TmjiQqIGA/Ktf8pCMrbr+giHmALh31G7bFI=
        -----END CERTIFICATE-----
    """.trimIndent()

    private val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
    private val stock = OkHttpClient().hostnameVerifier
    private val pin = sha256Hex(cert)

    private fun session(peer: () -> Array<Certificate>) = object : SSLSession {
        override fun getPeerCertificates(): Array<Certificate> = peer()
        override fun getId() = ByteArray(0)
        override fun getSessionContext() = null
        override fun getCreationTime() = 0L
        override fun getLastAccessedTime() = 0L
        override fun invalidate() {}
        override fun isValid() = true
        override fun putValue(name: String?, value: Any?) {}
        override fun getValue(name: String?): Any? = null
        override fun removeValue(name: String?) {}
        override fun getValueNames(): Array<String> = emptyArray()
        override fun getLocalCertificates(): Array<Certificate>? = null
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> =
            throw SSLPeerUnverifiedException("not used")
        override fun getPeerPrincipal(): Principal = cert.subjectX500Principal
        override fun getLocalPrincipal(): Principal? = null
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getProtocol() = "TLSv1.3"
        override fun getPeerHost(): String? = null
        override fun getPeerPort() = -1
        override fun getPacketBufferSize() = 0
        override fun getApplicationBufferSize() = 0
    }
    private val live = session { arrayOf(cert) }

    // WHY: the pinned leaf is the trust anchor, so a LAN IP the cert was never issued for is fine.
    @Test fun pinMatch_anyHost_accepts() {
        assertTrue(PinRelaxedHostnameVerifier(pin, stock).verify("192.168.1.10", live))
    }

    // WHY: without a pin match the stock check decides -- wrong name fails, right name passes.
    @Test fun pinMismatch_stockDecides() {
        val v = PinRelaxedHostnameVerifier("AA:BB", stock)
        assertFalse(v.verify("192.168.1.10", live))
        assertTrue(v.verify("attacker.example", live))
    }
    @Test fun noPin_wrongName_rejects() {
        assertFalse(PinRelaxedHostnameVerifier(null, stock).verify("192.168.1.10", live))
    }

    // WHY: no readable peer certificate must never turn into acceptance.
    @Test fun peerCertificatesThrows_failsClosed() {
        val broken = session { throw SSLPeerUnverifiedException("none") }
        assertFalse(PinRelaxedHostnameVerifier(pin, stock).verify("192.168.1.10", broken))
    }
}
