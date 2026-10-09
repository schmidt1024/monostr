package com.monostr.monero

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MoneroBase58Test {
    // Reference address produced by monero-python for spend secret 00..01 (see VectorsTest)
    private val refAddress =
        "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
    private val refBytes = (
        "12" +
            "1ed49357e217e79dab3c5503822f2bdb561e302e24476ee6ff33242c7551d4e7" +
            "8944790c0cfa9998c2f196061be89b2b8387f9d397db20ea8e049899cdc947d1"
        ).hexToBytes()

    @Test
    fun `encode of empty is empty`() {
        assertEquals("", MoneroBase58.encode(ByteArray(0)))
        assertArrayEquals(ByteArray(0), MoneroBase58.decode(""))
    }

    @Test
    fun `full block encodes to 11 chars with leading ones for zero`() {
        assertEquals("11111111111", MoneroBase58.encode(ByteArray(8)))
        assertEquals("11", MoneroBase58.encode(ByteArray(1)))
    }

    @Test
    fun `decode of reference address body yields prefix and keys`() {
        val decoded = MoneroBase58.decode(refAddress)
        assertEquals(69, decoded.size)
        assertArrayEquals(refBytes, decoded.copyOfRange(0, 65))
    }

    @Test
    fun `encode of reference bytes plus checksum yields reference address`() {
        val checksum = Keccak.hash256(refBytes).copyOfRange(0, 4)
        assertEquals(refAddress, MoneroBase58.encode(refBytes + checksum))
    }

    @Test
    fun `roundtrip random lengths`() {
        val rnd = Random(42)
        for (len in 0..80) {
            val data = rnd.nextBytes(len)
            assertArrayEquals(data, MoneroBase58.decode(MoneroBase58.encode(data)), "len=$len")
        }
    }

    @Test
    fun `decode rejects invalid characters and impossible lengths`() {
        assertThrows(IllegalArgumentException::class.java) { MoneroBase58.decode("0") }
        assertThrows(IllegalArgumentException::class.java) { MoneroBase58.decode("1") }   // no 1-char block size
        assertThrows(IllegalArgumentException::class.java) { MoneroBase58.decode("1111") } // no 4-char block size
    }

    @Test
    fun `decode rejects block overflow`() {
        // "zzzzzzzzzzz" as base58 exceeds 2^64
        assertThrows(IllegalArgumentException::class.java) { MoneroBase58.decode("zzzzzzzzzzz") }
    }

    @Test
    fun `decode rejects excluded characters at valid block length`() {
        for (bad in listOf("1111111111O", "1111111111I", "1111111111l", "11111111110")) {
            assertThrows(IllegalArgumentException::class.java, { MoneroBase58.decode(bad) }, bad)
        }
    }
}
