package com.monostr.tips.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventJsonTest {
    private val event = Event(
        id = "1".repeat(64),
        pubkey = "a".repeat(64),
        createdAt = 1700000000,
        kind = 9738,
        tags = listOf(listOf("e", "b".repeat(64)), listOf("watcher", "https://w.example", "c".repeat(64))),
        content = "Tip für dich & mehr",
        sig = "0".repeat(128),
    )

    @Test
    fun `roundtrip and NIP-01 field names`() {
        val json = EventJson.encode(event)
        assertTrue(json.contains("\"created_at\":1700000000"), json)
        assertTrue(json.contains("\"pubkey\":\""), json)
        assertEquals(event, EventJson.decode(json))
    }

    @Test
    fun `decode ignores unknown keys`() {
        val json = EventJson.encode(event).dropLast(1) + ",\"extra\":1}"
        assertEquals(event, EventJson.decode(json))
    }

    @Test
    fun `tag helpers`() {
        assertEquals(listOf("b".repeat(64)), event.tagValues("e"))
        assertEquals(listOf("watcher", "https://w.example", "c".repeat(64)), event.firstTag("watcher"))
        assertEquals("https://w.example", event.firstTagValue("watcher"))
        assertNull(event.firstTagValue("p"))
        assertEquals(emptyList<String>(), event.tagValues("p"))
    }
}
