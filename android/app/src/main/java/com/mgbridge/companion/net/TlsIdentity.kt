package com.mgbridge.companion.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * This device's TLS identity: an EC P-256 key living in AndroidKeyStore with the
 * self-signed certificate the keystore mints at generation time. The private key is
 * non-extractable; TLS reaches it through a KeyManagerFactory bound to the keystore.
 */
object TlsIdentity {

    private const val ALIAS = "mgbridge_identity"
    private const val STORE = "AndroidKeyStore"

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    @Synchronized
    fun ensure() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
            .setCertificateSubject(X500Principal("CN=mgbridge-android"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(start.time)
            .setCertificateNotAfter(end.time)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    fun certificate(): X509Certificate {
        ensure()
        return keyStore().getCertificate(ALIAS) as X509Certificate
    }

    /** Lowercase-hex SHA-256 of our own leaf DER — what peers pin and TXT advertises. */
    fun fingerprint(): String = PinningTrustManager.fingerprint(certificate())

    fun keyManagers(): Array<KeyManager> {
        ensure()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), null)
        return kmf.keyManagers
    }
}
