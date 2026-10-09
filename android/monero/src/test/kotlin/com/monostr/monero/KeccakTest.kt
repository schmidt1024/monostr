package com.monostr.monero

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeccakTest {
    @Test
    fun `empty input matches Keccak-256 reference`() {
        // Original Keccak-256 (not SHA3-256, whose empty hash starts with a7ffc6f8)
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            Keccak.hash256(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun `abc matches Keccak-256 reference`() {
        assertEquals(
            "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
            Keccak.hash256("abc".toByteArray()).toHex(),
        )
    }

    @Test
    fun `output is 32 bytes`() {
        assertEquals(32, Keccak.hash256(ByteArray(100)).size)
    }
}
