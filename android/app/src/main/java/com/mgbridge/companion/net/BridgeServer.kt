package com.mgbridge.companion.net

import android.content.Context
import android.util.Log
import com.mgbridge.companion.BridgeService
import com.mgbridge.companion.transfer.Receiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The phone's always-on listener: mutual-TLS server socket on an ephemeral port,
 * one coroutine per connection, sessions handled by [Receiver]. Unknown client
 * certs never get past the handshake ([PinningTrustManager]) — the phone only
 * pairs outbound, so its server has no pairing hole.
 */
class BridgeServer(private val ctx: Context, private val scope: CoroutineScope) {

    @Volatile var port: Int = 0
        private set

    private var serverSocket: SSLServerSocket? = null

    /** Binds and starts the accept loop; returns the bound port. Call off the main thread. */
    fun start(): Int {
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(
            TlsIdentity.keyManagers(),
            arrayOf(PinningTrustManager({ TrustStore.fingerprints(ctx) })),
            SecureRandom()
        )
        val ss = sslCtx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        ss.needClientAuth = true
        serverSocket = ss
        port = ss.localPort
        Log.i(BridgeService.TAG, "BridgeServer listening on :$port")

        scope.launch(Dispatchers.IO) {
            while (true) {
                val socket = try {
                    ss.accept() as SSLSocket
                } catch (e: Exception) {
                    if (serverSocket != null) Log.w(BridgeService.TAG, "accept failed: ${e.message}")
                    break // socket closed by stop()
                }
                launch {
                    try {
                        socket.soTimeout = 30_000
                        socket.startHandshake()
                        Receiver(ctx).handle(socket)
                    } catch (e: Exception) {
                        Log.w(BridgeService.TAG, "session error: ${e.message}")
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            }
        }
        return port
    }

    fun stop() {
        val ss = serverSocket
        serverSocket = null
        try { ss?.close() } catch (_: Exception) {}
        port = 0
    }
}
