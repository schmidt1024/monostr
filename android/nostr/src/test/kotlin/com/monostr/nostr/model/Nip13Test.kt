package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Nip13Test {
    private fun id(prefix: String) = prefix + "f".repeat(64 - prefix.length)
    private fun nonce(target: String) = listOf(listOf("nonce", "123", target))

    @Test
    fun `the id and the committed target both count, the lower one decides`() {
        assertEquals(8, Nip13.effectiveBits(id("00"), nonce("8")))
        assertEquals(4, Nip13.effectiveBits(id("00"), nonce("4")))
        assertEquals(8, Nip13.effectiveBits(id("00"), nonce("30")))
        assertEquals(21, Nip13.effectiveBits(id("000007"), nonce("21")))
        assertEquals(10, Nip13.effectiveBits(id("002"), nonce("20")))
    }

    @Test
    fun `no commitment means no proof of work, a lucky id does not count`() {
        assertEquals(0, Nip13.effectiveBits(id("00"), emptyList()))
        assertEquals(0, Nip13.effectiveBits(id("00"), listOf(listOf("nonce", "123"))))
        assertEquals(0, Nip13.effectiveBits(id("00"), nonce("abc")))
        assertEquals(0, Nip13.effectiveBits(id("ff"), nonce("8")))
    }

    @Test
    fun `the mapper sets the difficulty`() {
        val event = com.monostr.tips.event.Event(id("00"), "a".repeat(64), 1000, 1, nonce("8"), "hi", "s".repeat(128))
        assertEquals(8, NoteMapper.note(event)!!.powBits)
    }
}
