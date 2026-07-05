package com.mgbridge.companion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Toggles the mobile hotspot by driving the Settings UI, since no API lets a normal
 * app do it directly.
 *
 * Build #1: opens the tethering settings screen and logs the window it lands on, so we
 * can read the real node tree on-device (via `uiautomator dump`) and wire up the exact
 * switch click in the next iteration. One UI's layout varies, so this is tuned against
 * the actual device rather than guessed.
 */
class HotspotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HotspotAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(BridgeService.TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Node-tree walking to find and click the hotspot switch comes in the next step.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(BridgeService.TAG, "window: ${event.packageName} / ${event.className}")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        Log.i(BridgeService.TAG, "AccessibilityService disconnected")
        super.onDestroy()
    }

    /** Kick off a hotspot on/off. For now, surfaces the tethering settings screen. */
    fun performToggle(ctx: Context, on: Boolean) {
        Log.i(BridgeService.TAG, "performToggle(on=$on) — opening tethering settings")
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName("com.android.settings", "com.android.settings.TetherSettings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(BridgeService.TAG, "TetherSettings intent failed (${e.message}); trying generic wireless settings")
            try {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Log.e(BridgeService.TAG, "No settings screen reachable: ${e2.message}")
            }
        }
    }
}
