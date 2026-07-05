package com.mgbridge.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PairingTest {

    // Shared vectors — the Swift tests assert the same proofs.
    @Test
    fun proofVectors() {
        assertEquals(
            "9ba8a5714f8501210228a623d0ed86cb5a5d3473f9e6d4ca1ddde49941f151fd",
            Pairing.proof("MGBR1DGE", "aa".repeat(32), "bb".repeat(32))
        )
        assertEquals(
            "a49f7672b7b20639d52747836503bac08a452dbbc79d4e16ed6b0d2c4ae1bae5",
            Pairing.proof("7Y2KQ0ZX", "0123456789abcdef".repeat(4), "fedcba9876543210".repeat(4))
        )
    }

    @Test
    fun proofIsCaseInsensitiveOnFingerprintsOnly() {
        val a = Pairing.proof("MGBR1DGE", "AA".repeat(32), "BB".repeat(32))
        assertEquals(Pairing.proof("MGBR1DGE", "aa".repeat(32), "bb".repeat(32)), a)
        assertNotEquals(a, Pairing.proof("mgbr1dge", "aa".repeat(32), "bb".repeat(32)))
    }

    @Test
    fun swappedFingerprintsChangeProof() {
        assertNotEquals(
            Pairing.proof("MGBR1DGE", "aa".repeat(32), "bb".repeat(32)),
            Pairing.proof("MGBR1DGE", "bb".repeat(32), "aa".repeat(32))
        )
    }

    @Test
    fun tokensUseCrockfordAlphabet() {
        val rng = SecureRandom()
        repeat(50) {
            val t = Pairing.newToken(rng)
            assertEquals(Pairing.TOKEN_LENGTH, t.length)
            assertTrue(t.all { it in Pairing.ALPHABET })
        }
    }

    @Test
    fun normalizeMapsConfusablesAndSeparators() {
        assertEquals("MGBR1DGE", Pairing.normalizeToken(" mgbr-idge "))
        assertEquals("0000", Pairing.normalizeToken("oOoO"))
        assertEquals("1111", Pairing.normalizeToken("iIlL"))
        assertEquals("AB12", Pairing.normalizeToken("ab_12"))
    }

    @Test
    fun constantTimeEqualsBehaves() {
        assertTrue(Pairing.proofEquals("abc123", "abc123"))
        assertFalse(Pairing.proofEquals("abc123", "abc124"))
        assertFalse(Pairing.proofEquals("abc", "abcd"))
    }
}
