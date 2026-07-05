package com.mgbridge.companion.net

import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Outbound mutual-TLS connections (send + pairing). Same identity and pinning as the
 * server; during pairing the unknown server is admitted and its live fingerprint is
 * reported so the HMAC proof can bind it.
 */
object BridgeClient {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val HANDSHAKE_TIMEOUT_MS = 15_000

    fun connect(
        ctx: Context,
        host: InetAddress,
        port: Int,
        pairing: Boolean = false,
        onPeerFp: ((String) -> Unit)? = null
    ): SSLSocket {
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(
            TlsIdentity.keyManagers(),
            arrayOf(
                PinningTrustManager(
                    { TrustStore.fingerprints(ctx) },
                    pairingMode = { pairing },
                    onPeerSeen = onPeerFp
                )
            ),
            SecureRandom()
        )
        val socket = sslCtx.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = 30_000
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
        return socket
    }
}
