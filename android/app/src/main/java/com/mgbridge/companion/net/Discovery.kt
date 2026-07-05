package com.mgbridge.companion.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.mgbridge.companion.BridgeService
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Bonjour presence: registers `_mgbridge._tcp` for our server, and browses/resolves
 * peers for the send/pair paths. TXT carries protocol version and a fingerprint
 * prefix so the UI can label peers before any connection.
 */
class Discovery(private val ctx: Context) {

    companion object {
        const val SERVICE_TYPE = "_mgbridge._tcp"

        fun deviceName(ctx: Context): String =
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    data class Peer(val name: String, val host: InetAddress, val port: Int, val fpPrefix: String?)

    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    // --- advertise ---

    private var registration: NsdManager.RegistrationListener? = null

    fun register(port: Int, fingerprint: String) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName(ctx)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", PROTOCOL_VERSION.toString())
            setAttribute("fp", fingerprint.take(16))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(BridgeService.TAG, "NSD registered as '${i.serviceName}' :$port")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, err: Int) {
                Log.e(BridgeService.TAG, "NSD registration failed: $err")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, err: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        registration?.let {
            try { nsd.unregisterService(it) } catch (_: Exception) {}
        }
        registration = null
    }

    // --- browse + resolve (pair/send paths) ---

    private var discovery: NsdManager.DiscoveryListener? = null

    /** Discovers peers and resolves each to host/port. Callbacks arrive on arbitrary threads. */
    fun browse(onPeer: (Peer) -> Unit, onLost: (name: String) -> Unit = {}) {
        val self = deviceName(ctx)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onStartDiscoveryFailed(type: String, err: Int) {
                Log.e(BridgeService.TAG, "NSD browse failed to start: $err")
            }
            override fun onStopDiscoveryFailed(type: String, err: Int) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == self) return // that's us
                resolve(i, onPeer)
            }
            override fun onServiceLost(i: NsdServiceInfo) = onLost(i.serviceName)
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopBrowse() {
        discovery?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discovery = null
    }

    private fun resolve(info: NsdServiceInfo, onPeer: (Peer) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsd.registerServiceInfoCallback(info, executor, object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(i: NsdServiceInfo) {
                    val host = i.hostAddresses.firstOrNull() ?: return
                    onPeer(Peer(i.serviceName, host, i.port, txt(i, "fp")))
                    try { nsd.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackRegistrationFailed(err: Int) {
                    Log.w(BridgeService.TAG, "resolve callback failed: $err")
                }
                override fun onServiceInfoCallbackUnregistered() {}
            })
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(i: NsdServiceInfo) {
                    @Suppress("DEPRECATION") val host = i.host ?: return
                    onPeer(Peer(i.serviceName, host, i.port, txt(i, "fp")))
                }
                override fun onResolveFailed(i: NsdServiceInfo, err: Int) {
                    Log.w(BridgeService.TAG, "resolve failed for ${i.serviceName}: $err")
                }
            })
        }
    }

    private fun txt(i: NsdServiceInfo, key: String): String? =
        i.attributes[key]?.let { String(it, Charsets.UTF_8) }
}
