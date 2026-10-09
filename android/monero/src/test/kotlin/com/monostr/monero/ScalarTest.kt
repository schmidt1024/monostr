package com.monostr.monero

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

class ScalarTest {
    private val one = ByteArray(32).also { it[0] = 1 } // LE: 1

    @Test
    fun `L has the known value`() {
        assertEquals(
            BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989"),
            Scalar.L,
        )
    }

    @Test
    fun `scalar 1 times base point is the ed25519 base point`() {
        assertEquals(
            "5866666666666666666666666666666666666666666666666666666666666666",
            Scalar.mulBase(one).toHex(),
        )
    }

    @Test
    fun `reference spend secret yields reference spend public`() {
        // From monero-python: Seed("00..01").public_spend_key()
        val secret = "0000000000000000000000000000000000000000000000000000000000000001".hexToBytes()
        assertEquals(
            "1ed49357e217e79dab3c5503822f2bdb561e302e24476ee6ff33242c7551d4e7",
            Scalar.mulBase(secret).toHex(),
        )
    }

    @Test
    fun `reduce32 maps L to zero and L+1 to one`() {
        assertArrayEquals(ByteArray(32), Scalar.reduce32(Scalar.fromBigInteger(Scalar.L)))
        assertArrayEquals(one, Scalar.reduce32(Scalar.fromBigInteger(Scalar.L.add(BigInteger.ONE))))
    }

    @Test
    fun `reduce32 leaves canonical scalars untouched`() {
        val s = "0000000000000000000000000000000000000000000000000000000000000001".hexToBytes()
        assertArrayEquals(s, Scalar.reduce32(s))
    }

    @Test
    fun `isCanonical`() {
        assertTrue(Scalar.isCanonical(one))
        assertTrue(Scalar.isCanonical(Scalar.fromBigInteger(Scalar.L.subtract(BigInteger.ONE))))
        assertFalse(Scalar.isCanonical(Scalar.fromBigInteger(Scalar.L)))
        assertFalse(Scalar.isCanonical(ByteArray(32) { 0xff.toByte() }))
    }

    @Test
    fun `bigInteger roundtrip is little endian`() {
        val n = BigInteger.valueOf(0x0102)
        val le = Scalar.fromBigInteger(n)
        assertEquals(2, le[0].toInt())
        assertEquals(1, le[1].toInt())
        assertEquals(n, Scalar.toBigInteger(le))
    }
}
