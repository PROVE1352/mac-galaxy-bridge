package com.mgbridge.companion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Toggles the mobile hotspot by driving One UI's "Mobile Hotspot and Tethering" screen
 * (Settings$SecTetherSettingsActivity), since no API lets a normal app do it directly
 * (verified: even shell uid fails with TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION).
 *
 * Flow: [performToggle] records the desired state, opens the tethering screen, and both
 * accessibility events and a retry timer drive [attempt], which finds the Mobile-Hotspot
 * row's switch, clicks it only if its state differs, then backs out of Settings.
 */
class HotspotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HotspotAccessibilityService? = null

        // Row title (not the toolbar "모바일 핫스팟 및 테더링"). Localized fallbacks included.
        private val HOTSPOT_TITLES = setOf("모바일 핫스팟", "Mobile Hotspot", "Mobile hotspot")
        private const val SWITCH_ID = "android:id/switch_widget"
        private const val SETTINGS_PKG = "com.android.settings"
        private val RETRY_DELAYS_MS = longArrayOf(300, 650, 1100, 1700, 2500, 3500)
        private const val GIVE_UP_MS = 5000L
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var pendingOn: Boolean? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(BridgeService.TAG, "AccessibilityService connected")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        Log.i(BridgeService.TAG, "AccessibilityService disconnected")
        super.onDestroy()
    }

    /** Request the hotspot be [on]; opens the tethering screen and drives the toggle. */
    fun performToggle(ctx: Context, on: Boolean) {
        pendingOn = on
        Log.i(BridgeService.TAG, "performToggle(on=$on) — opening tethering settings")
        // Launch from the accessibility service's own context: it is BAL-exempt, whereas the
        // background foreground-service context is not (verified: startActivity silently no-ops).
        openTetherSettings(this)
        handler.removeCallbacksAndMessages(null)
        for (d in RETRY_DELAYS_MS) handler.postDelayed({ attempt() }, d)
        handler.postDelayed({
            if (pendingOn != null) { Log.w(BridgeService.TAG, "toggle gave up (screen never resolved)"); pendingOn = null }
        }, GIVE_UP_MS)
    }

    private fun openTetherSettings(ctx: Context) {
        val intents = listOf(
            Intent().setClassName(SETTINGS_PKG, "com.android.settings.Settings\$SecTetherSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(i)
                return
            } catch (e: Exception) {
                Log.w(BridgeService.TAG, "settings intent failed: ${e.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pendingOn != null && event?.packageName == SETTINGS_PKG) attempt()
    }

    /** One attempt to find and set the hotspot switch. No-op (returns) if the screen isn't ready. */
    private fun attempt() {
        val desired = pendingOn ?: return
        val root = settingsRoot()
        if (root == null) { Log.d(BridgeService.TAG, "attempt: no settings root yet"); return }
        val sw = findHotspotSwitch(root)
        if (sw == null) { Log.d(BridgeService.TAG, "attempt: hotspot switch not found yet"); return }

        val isOn = sw.isChecked
        if (isOn != desired) {
            val target = if (sw.isClickable) sw else sw.parent
            val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            Log.i(BridgeService.TAG, "hotspot switch was=$isOn -> click(desired=$desired) success=$ok")
        } else {
            Log.i(BridgeService.TAG, "hotspot already ${if (isOn) "on" else "off"}; nothing to do")
        }
        pendingOn = null
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400)
    }

    private fun settingsRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val wins = windows
        Log.d(BridgeService.TAG, "settingsRoot: active=${active?.packageName} windows=${wins.size} pkgs=${wins.map { it.root?.packageName }}")
        active?.let { if (it.packageName == SETTINGS_PKG) return it }
        for (w in wins) {
            val r = w.root ?: continue
            if (r.packageName == SETTINGS_PKG) return r
        }
        return active
    }

    /** Find the Switch belonging to the Mobile-Hotspot row (nearest switch to that title). */
    private fun findHotspotSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val switches = root.findAccessibilityNodeInfosByViewId(SWITCH_ID)
        Log.d(BridgeService.TAG, "findHotspotSwitch: switchCount=${switches?.size ?: -1}")
        if (switches.isNullOrEmpty()) return null

        var titleY = -1
        outer@ for (title in HOTSPOT_TITLES) {
            for (n in root.findAccessibilityNodeInfosByText(title)) {
                if (n.text?.toString()?.trim() in HOTSPOT_TITLES) {
                    val r = Rect(); n.getBoundsInScreen(r); titleY = r.centerY(); break@outer
                }
            }
        }

        fun centerY(n: AccessibilityNodeInfo): Int { val r = Rect(); n.getBoundsInScreen(r); return r.centerY() }
        return if (titleY < 0) switches.minByOrNull { centerY(it) }        // fallback: topmost switch
        else switches.minByOrNull { kotlin.math.abs(centerY(it) - titleY) }
    }
}
