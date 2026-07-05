package com.mgbridge.companion.net

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust = "this exact certificate", nothing else: a peer is accepted iff the SHA-256 of
 * its leaf DER is in the trusted set. No CAs, no chains, no expiry games — the KDE
 * Connect model for a fixed set of personal devices.
 *
 * [pairingMode] opens a deliberate hole: during an armed pairing window the unknown
 * peer is admitted at the TLS layer so the pairing HMAC (which binds both live
 * fingerprints) can decide instead. [onPeerSeen] always reports the fingerprint the
 * handshake actually presented — pairing uses it to compute/verify the proof.
 *
 * Pure JVM on purpose; unit-tested with fixture certs.
 */
class PinningTrustManager(
    private val trustedFingerprints: () -> Set<String>,
    private val pairingMode: () -> Boolean = { false },
    private val onPeerSeen: ((fpHex: String) -> Unit)? = null
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = check(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    private fun check(chain: Array<X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fp = fingerprint(leaf)
        onPeerSeen?.invoke(fp)
        if (fp in trustedFingerprints()) return
        if (pairingMode()) return
        throw CertificateException("untrusted peer: $fp")
    }

    companion object {
        /** Lowercase-hex SHA-256 of the certificate's DER encoding. */
        fun fingerprint(cert: X509Certificate): String = Framing.sha256Hex(cert.encoded)
    }
}
