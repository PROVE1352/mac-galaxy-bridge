package com.mgbridge.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinningTrustManagerTest {

    private fun fixture(name: String): X509Certificate =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private val certA by lazy { fixture("cert_a.pem") }
    private val certB by lazy { fixture("cert_b.pem") }

    @Test
    fun fingerprintIsLowercaseHexSha256OfDer() {
        val fp = PinningTrustManager.fingerprint(certA)
        assertEquals(64, fp.length)
        assertEquals(fp, fp.lowercase())
        assertEquals(Framing.sha256Hex(certA.encoded), fp)
    }

    @Test
    fun trustedPeerPasses() {
        val tm = PinningTrustManager({ setOf(PinningTrustManager.fingerprint(certA)) })
        tm.checkServerTrusted(arrayOf(certA), "ECDHE_ECDSA")
        tm.checkClientTrusted(arrayOf(certA), "ECDHE_ECDSA")
    }

    @Test
    fun unknownPeerRejected() {
        val tm = PinningTrustManager({ setOf(PinningTrustManager.fingerprint(certA)) })
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(certB), "ECDHE_ECDSA")
        }
    }

    @Test
    fun emptyChainRejected() {
        val tm = PinningTrustManager({ setOf(PinningTrustManager.fingerprint(certA)) })
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(), "x") }
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(null, "x") }
    }

    @Test
    fun pairingModeAdmitsUnknownAndReportsFingerprint() {
        var seen: String? = null
        val tm = PinningTrustManager({ emptySet() }, pairingMode = { true }, onPeerSeen = { seen = it })
        tm.checkServerTrusted(arrayOf(certB), "ECDHE_ECDSA")
        assertEquals(PinningTrustManager.fingerprint(certB), seen)
    }

    @Test
    fun pairingModeOffRejectsAgain() {
        var armed = true
        val tm = PinningTrustManager({ emptySet() }, pairingMode = { armed })
        tm.checkServerTrusted(arrayOf(certB), "ECDHE_ECDSA")
        armed = false
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(certB), "ECDHE_ECDSA")
        }
    }

    @Test
    fun onlyLeafMattersInAChain() {
        val tm = PinningTrustManager({ setOf(PinningTrustManager.fingerprint(certA)) })
        tm.checkServerTrusted(arrayOf(certA, certB), "ECDHE_ECDSA")
    }
}
