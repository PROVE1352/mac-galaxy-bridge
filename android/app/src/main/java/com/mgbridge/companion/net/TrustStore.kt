package com.mgbridge.companion.net

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paired peers (name + pinned certificate fingerprint) in SharedPreferences.
 * Fingerprints are public data — no encryption theater needed.
 */
object TrustStore {

    data class Peer(val name: String, val fp: String)

    private const val PREFS = "mgbridge_trust"
    private const val KEY_PEERS = "peers"

    /** Phone-side pairing window: while set (and not expired) the TLS layer admits unknowns. */
    @Volatile private var pairingUntilElapsedMs: Long = 0

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun peers(ctx: Context): List<Peer> {
        val raw = sp(ctx).getString(KEY_PEERS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Peer(o.getString("name"), o.getString("fp"))
        }
    }

    fun fingerprints(ctx: Context): Set<String> = peers(ctx).mapTo(HashSet()) { it.fp }

    fun isTrusted(ctx: Context, fp: String): Boolean = fp.lowercase() in fingerprints(ctx)

    fun addPeer(ctx: Context, name: String, fp: String) {
        val kept = peers(ctx).filter { it.fp != fp.lowercase() }
        val arr = JSONArray()
        for (p in kept + Peer(name, fp.lowercase())) {
            arr.put(JSONObject().put("name", p.name).put("fp", p.fp))
        }
        sp(ctx).edit().putString(KEY_PEERS, arr.toString()).apply()
    }

    fun removePeer(ctx: Context, fp: String) {
        val kept = peers(ctx).filter { it.fp != fp.lowercase() }
        val arr = JSONArray()
        for (p in kept) arr.put(JSONObject().put("name", p.name).put("fp", p.fp))
        sp(ctx).edit().putString(KEY_PEERS, arr.toString()).apply()
    }

    fun armPairing(windowMs: Long = 2 * 60_000) {
        pairingUntilElapsedMs = SystemClock.elapsedRealtime() + windowMs
    }

    fun disarmPairing() {
        pairingUntilElapsedMs = 0
    }

    fun pairingArmed(): Boolean = SystemClock.elapsedRealtime() < pairingUntilElapsedMs
}
