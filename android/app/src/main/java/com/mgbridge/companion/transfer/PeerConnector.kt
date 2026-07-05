package com.mgbridge.companion.transfer

import android.content.Context
import android.util.Log
import com.mgbridge.companion.BridgeService
import com.mgbridge.companion.net.BridgeClient
import com.mgbridge.companion.net.Discovery
import com.mgbridge.companion.net.TrustStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import javax.net.ssl.SSLSocket

/**
 * Finds a *paired* peer on the network and hands back a connected, pinned socket.
 * TXT fingerprint prefixes pre-filter candidates; the TLS handshake is the real gate.
 */
object PeerConnector {

    class NoPeerException : Exception("no paired peer reachable")

    class Connection(val socket: SSLSocket, val peerName: String) : AutoCloseable {
        override fun close() = socket.close()
    }

    suspend fun connect(
        ctx: Context,
        preferredName: String? = null,
        timeoutMs: Long = 12_000
    ): Connection {
        val trustedPrefixes = TrustStore.fingerprints(ctx).mapTo(HashSet()) { it.take(16) }
        if (trustedPrefixes.isEmpty()) throw NoPeerException()

        val disc = Discovery(ctx)
        val found = Channel<Discovery.Peer>(Channel.UNLIMITED)
        disc.browse({ found.trySend(it) })
        try {
            return withTimeout(timeoutMs) {
                val tried = HashSet<String>()
                while (true) {
                    val peer = found.receive()
                    if (preferredName != null && peer.name != preferredName) continue
                    if (peer.fpPrefix != null && peer.fpPrefix !in trustedPrefixes) continue
                    val key = "${peer.host.hostAddress}:${peer.port}"
                    if (!tried.add(key)) continue
                    try {
                        return@withTimeout Connection(
                            BridgeClient.connect(ctx, peer.host, peer.port),
                            peer.name
                        )
                    } catch (e: Exception) {
                        Log.w(BridgeService.TAG, "connect to ${peer.name} failed: ${e.message}")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw NoPeerException()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw NoPeerException()
        } finally {
            disc.stopBrowse()
            found.close()
        }
    }
}
