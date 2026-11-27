package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Hex64Test {
    @Test
    fun `only 64 lowercase hex characters are an id or pubkey`() {
        assertTrue(Hex64.isValid("0123456789abcdef".repeat(4)))
        for (bad in listOf("", "a/b", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "a".repeat(62) + "/.", " " + "a".repeat(63))) assertFalse(Hex64.isValid(bad), bad)
    }

    @Test
    fun `a bad value becomes one that names nothing`() {
        val id = "b".repeat(64)
        assertEquals(id, Hex64.orNone(id))
        assertEquals("0".repeat(64), Hex64.orNone("a/b"))
    }
}
