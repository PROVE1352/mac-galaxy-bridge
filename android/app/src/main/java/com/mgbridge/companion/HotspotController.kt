package com.mgbridge.companion

import android.content.Context
import android.util.Log

/**
 * Entry point for turning the mobile hotspot on/off.
 *
 * The tethering API (TetheringManager.startTethering) is unreachable for a normal app
 * (verified: even shell uid fails with TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION),
 * so we drive the Settings UI via [HotspotAccessibilityService]. That service does the
 * actual work; this object is just the seam the rest of the app calls.
 */
object HotspotController {
    @Volatile
    var desiredOn: Boolean = false
        private set

    fun request(ctx: Context, on: Boolean) {
        desiredOn = on
        val svc = HotspotAccessibilityService.instance
        if (svc == null) {
            Log.w(BridgeService.TAG, "Hotspot $on requested but AccessibilityService is OFF — enable it in Settings")
            return
        }
        svc.performToggle(ctx, on)
    }
}
