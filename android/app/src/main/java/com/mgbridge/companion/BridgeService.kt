package com.mgbridge.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mgbridge.companion.net.BridgeServer
import com.mgbridge.companion.net.Discovery
import com.mgbridge.companion.net.TlsIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Always-on foreground service. Holds a runtime-registered receiver for the Mac's
 * Bluetooth ACL connect/disconnect events — which fire reliably (verified on device),
 * unlike Samsung's routine trigger which gates on profile-level connection — and owns
 * the transfer server: TLS listener + Bonjour registration, torn down with the service.
 */
class BridgeService : Service() {

    companion object {
        const val TAG = "MGBridge"
        const val CHANNEL_ID = "mgbridge_status"
        const val NOTIF_ID = 1
        @Volatile var running = false
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            Log.i(TAG, "onReceive action=${intent.action}")
            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val addr = device?.address ?: return
            val target = Prefs.macAddr(ctx)
            val isMac = target.isNotBlank() && addr.equals(target, ignoreCase = true)

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.i(TAG, "ACL_CONNECTED addr=$addr isMac=$isMac (target=$target)")
                    if (isMac) onMacConnected()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "ACL_DISCONNECTED addr=$addr isMac=$isMac")
                    if (isMac) onMacDisconnected()
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: BridgeServer? = null
    private var discovery: Discovery? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Watching for your Mac…"))

        scope.launch {
            try {
                val srv = BridgeServer(this@BridgeService, scope)
                val disc = Discovery(this@BridgeService)
                server = srv
                discovery = disc
                val port = srv.start()
                disc.register(port, TlsIdentity.fingerprint())
            } catch (e: Exception) {
                Log.e(TAG, "transfer server failed to start: ${e.message}", e)
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // ACL_CONNECTED is a protected system broadcast; EXPORTED is the correct flag.
            registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(btReceiver, filter)
        }
        Log.i(TAG, "BridgeService started; watching Mac=${Prefs.macAddr(this)}")
    }

    private fun onMacConnected() {
        Log.i(TAG, "Mac connected -> request hotspot ON")
        updateNotification("Mac connected — turning hotspot on")
        HotspotController.request(this, true)
    }

    private fun onMacDisconnected() {
        Log.i(TAG, "Mac disconnected -> request hotspot OFF")
        updateNotification("Watching for your Mac…")
        HotspotController.request(this, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try {
            unregisterReceiver(btReceiver)
        } catch (_: Exception) {
        }
        discovery?.unregister()
        server?.stop()
        scope.cancel()
        Log.i(TAG, "BridgeService stopped")
        super.onDestroy()
    }

    // --- notification plumbing ---

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Bridge status",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps the bridge running and shows Mac connection status" }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mac-Galaxy Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
