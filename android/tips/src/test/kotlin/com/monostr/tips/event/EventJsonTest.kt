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
    fun `a timestamp past the signed range reads as the largest one instead of failing`() {
        val json = EventJson.encode(event).replace("\"created_at\":1700000000", "\"created_at\":9223372036854775808")
        assertEquals(event.copy(createdAt = Long.MAX_VALUE), EventJson.decode(json))
        val max = EventJson.encode(event).replace("\"created_at\":1700000000", "\"created_at\":18446744073709551615")
        assertEquals(Long.MAX_VALUE, EventJson.decode(max).createdAt)
    }

    @Test
    fun `any other broken event still fails to decode`() {
        for (bad in listOf("\"created_at\":\"99999999999999999999\"", "\"created_at\":-99999999999999999999", "\"created_at\":1.5e30", "\"created_at\":null")) {
            val json = EventJson.encode(event).replace("\"created_at\":1700000000", bad)
            assertTrue(runCatching { EventJson.decode(json) }.isFailure, bad)
        }
        assertTrue(runCatching { EventJson.decode("{\"kind\":99999999999999999999}") }.isFailure)
        assertTrue(runCatching { EventJson.decode("not json") }.isFailure)
    }

    @Test
    fun `far future is a day past now and does not overflow`() {
        val now = 1_700_000_000L
        assertTrue(EventJson.isFarFuture(Long.MAX_VALUE, now))
        assertTrue(EventJson.isFarFuture(now + EventJson.FAR_FUTURE_SECONDS + 1, now))
        assertTrue(!EventJson.isFarFuture(now + EventJson.FAR_FUTURE_SECONDS, now))
        assertTrue(!EventJson.isFarFuture(now + 900, now), "a clock that runs ahead is not bogus")
        assertTrue(!EventJson.isFarFuture(0, now))
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
