package com.monostr.tips.event

import com.monostr.monero.toHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventIdTest {
    @Test
    fun `id matches NIP-01 reference with tags and unicode content`() {
        val unsigned = UnsignedEvent(
            kind = 9738,
            content = "Tip für dich & mehr",
            tags = listOf(
                listOf("e", "b".repeat(64)),
                listOf("p", "c".repeat(64)),
                listOf("amount", "5000000000"),
                listOf("pid", "0123456789abcdef"),
                listOf("type", "like"),
                listOf("expiration", "1700086400"),
            ),
            createdAt = 1700000000,
        )
        assertEquals(
            "d8cdc9bea0c5953d8026dbe87753397e720b5a45d4f79beb69f0fb2e65c88602",
            EventId.compute("a".repeat(64), unsigned),
        )
    }

    @Test
    fun `id matches NIP-01 reference for empty event`() {
        val unsigned = UnsignedEvent(kind = 10037, content = "", tags = emptyList(), createdAt = 1700000000)
        assertEquals(
            "728cbca9c6b9d6e610785ee3e68fcd46564b02c62aac8f920b7946fc62d0840c",
            EventId.compute("a".repeat(64), unsigned),
        )
    }

    @Test
    fun `sha256 of empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            EventId.sha256(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun `isHex64 accepts only 64 lowercase hex chars`() {
        assertTrue(isHex64("a".repeat(64)))
        assertTrue(isHex64("0123456789abcdef".repeat(4)))
        assertFalse(isHex64("A".repeat(64)))
        assertFalse(isHex64("a".repeat(63)))
        assertFalse(isHex64("g".repeat(64)))
        assertFalse(isHex64(""))
    }
}
