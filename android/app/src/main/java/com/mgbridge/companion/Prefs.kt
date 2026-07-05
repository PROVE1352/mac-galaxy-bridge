package com.mgbridge.companion

import android.content.Context

/** Tiny SharedPreferences wrapper for the bridge's config. */
object Prefs {
    private const val NAME = "mgbridge"

    // The Mac's Bluetooth address (this dev Mac defaults here for convenience).
    private const val DEFAULT_MAC = "A0:9A:8E:78:7C:8B"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun macAddr(ctx: Context): String = sp(ctx).getString("mac_addr", DEFAULT_MAC) ?: DEFAULT_MAC

    fun setMacAddr(ctx: Context, value: String) =
        sp(ctx).edit().putString("mac_addr", value.trim().uppercase()).apply()
}
