package com.monostr.monero

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HexTest {
    @Test
    fun `hexToBytes decodes lower and upper case`() {
        assertArrayEquals(byteArrayOf(0x00, 0x7f, 0xff.toByte()), "007fff".hexToBytes())
        assertArrayEquals(byteArrayOf(0xab.toByte()), "AB".hexToBytes())
    }

    @Test
    fun `toHex produces lower case`() {
        assertEquals("007fff", byteArrayOf(0x00, 0x7f, 0xff.toByte()).toHex())
        assertEquals("", ByteArray(0).toHex())
    }

    @Test
    fun `hexToBytes rejects odd length and bad chars`() {
        assertThrows(IllegalArgumentException::class.java) { "abc".hexToBytes() }
        assertThrows(IllegalArgumentException::class.java) { "zz".hexToBytes() }
    }

    @Test
    fun `hexToBytes handles empty string and rejects non-ascii digits`() {
        assertArrayEquals(ByteArray(0), "".hexToBytes())
        assertThrows(IllegalArgumentException::class.java) { "٣٣".hexToBytes() } // Arabic-Indic 3
        assertThrows(IllegalArgumentException::class.java) { "１１".hexToBytes() } // fullwidth 1
    }
}
