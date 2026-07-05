package com.mgbridge.companion.net

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing-code crypto. The Mac shows an 8-char Crockford-base32 token; the phone sends
 * `proof = HMAC-SHA256(key = token, msg = clientFpHex + serverFpHex)` where the
 * fingerprints are the lowercase-hex SHA-256 of each side's TLS leaf cert **as seen in
 * the live handshake** — a relay MITM sees different fingerprints and cannot forge it.
 */
object Pairing {

    /** Crockford base32: no I, L, O, U — nothing to misread. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val TOKEN_LENGTH = 8

    fun newToken(rng: SecureRandom = SecureRandom()): String =
        buildString(TOKEN_LENGTH) { repeat(TOKEN_LENGTH) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) } }

    /** Forgiving user input: trims separators, uppercases, maps O→0 and I/L→1. */
    fun normalizeToken(input: String): String = buildString {
        for (c in input.trim().uppercase()) {
            when (c) {
                ' ', '-', '_' -> {}
                'O' -> append('0')
                'I', 'L' -> append('1')
                else -> append(c)
            }
        }
    }

    /** Both sides must lowercase the fingerprints; client's first. Returns lowercase hex. */
    fun proof(token: String, clientFpHex: String, serverFpHex: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val msg = (clientFpHex.lowercase() + serverFpHex.lowercase()).toByteArray(Charsets.UTF_8)
        return Framing.toHex(mac.doFinal(msg))
    }

    /** Constant-time comparison so a proof check can't leak by timing. */
    fun proofEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
