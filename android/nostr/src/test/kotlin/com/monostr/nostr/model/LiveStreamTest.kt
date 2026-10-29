package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LiveStreamTest {
    private val host = "cf45a6ba1363ad7ed213a078e710d24115ae721c9b47bd1ebf4458eaefb4c2a5"
    private val hls = "https://api-uk.zap.stream/8ba03e88/hls/live.m3u8"

    /** The trigger event of the spec (zap.stream, 2026-10-19), with [change] applied to its tags. */
    private fun event(createdAt: Long = 1_792_421_602, change: (List<List<String>>) -> List<List<String>> = { it }) = AddressableEvent(
        "e".repeat(64), 30311, host, "8ba03e88-e573-449a-9241-35a6f136afce", createdAt,
        change(
            listOf(
                listOf("d", "8ba03e88-e573-449a-9241-35a6f136afce"), listOf("status", "live"), listOf("starts", "1792419595"),
                listOf("title", "Elon Makes MASSIVE SpaceX Pledge to the Kids"),
                listOf("image", "https://api-uk.zap.stream/657de7dd/thumb.webp?n=1"), listOf("current_participants", "23"),
                listOf("p", "a".repeat(64), "", "Host"),
                listOf("streaming", hls), listOf("streaming", "moq://api-uk.zap.stream:1443/"),
            ),
        ),
        "",
    )

    private fun List<List<String>>.set(name: String, value: String) = map { if (it[0] == name) listOf(name, value) else it }

    @Test
    fun `the trigger event parses`() {
        val s = LiveStream.parse(event())!!
        assertEquals("Elon Makes MASSIVE SpaceX Pledge to the Kids", s.title)
        assertEquals(LiveStatus.LIVE, s.status)
        assertEquals(23, s.currentParticipants)
        assertEquals("a".repeat(64), s.host)
        assertEquals("https://api-uk.zap.stream/657de7dd/thumb.webp?n=1", s.image)
    }

    @Test
    fun `a host key that is not 64 hex characters falls back to the author, an upper case one is lowered`() {
        fun withHost(key: String) = LiveStream.parse(event { tags -> tags.filterNot { it[0] == "p" } + listOf(listOf("p", key, "", "host")) })!!.host
        assertEquals(host, withHost("z".repeat(64)))
        assertEquals(host, withHost("a".repeat(63) + "\n"))
        assertEquals(host, withHost("ab".repeat(33)))
        assertEquals("ab".repeat(32), withHost("AB".repeat(32)))
    }

    @Test
    fun `a live event not updated for an hour counts as ended`() {
        val s = LiveStream.parse(event(createdAt = 1_000))!!
        assertEquals(LiveStatus.ENDED, s.effectiveStatus(now = 1_000 + 3_601))
        assertEquals(LiveStatus.LIVE, s.effectiveStatus(now = 1_000 + 3_599))
    }

    @Test
    fun `thumb and ends are read, status live is ended after an hour`() {
        val s = LiveStream.parse(event(createdAt = 5_000) { it + listOf(listOf("thumb", "https://x.example/t.png"), listOf("ends", "99")) })!!
        assertEquals("https://x.example/t.png", s.thumb)
        assertEquals(99L, s.ends)
        assertEquals(LiveStatus.ENDED, s.effectiveStatus(5_000 + 3_601))
        assertEquals(LiveStatus.LIVE, s.effectiveStatus(5_000 + 100))
    }

    @Test
    fun `unknown status is ended, bad participants are null, no host tag means the author`() {
        val odd = event { tags -> tags.set("status", "weird").set("current_participants", "-4").filterNot { it[0] == "p" } }
        val s = LiveStream.parse(odd)!!
        assertEquals(LiveStatus.ENDED, s.status)
        assertNull(s.currentParticipants)
        assertEquals(host, s.host)
        assertNull(LiveStream.parse(event().copy(kind = 30023)))
    }

    @Test
    fun `pictures named by others must be https on a public host`() {
        assertNull(LiveStream.pictureUrl("http://x.example/a.png"))
        assertNull(LiveStream.pictureUrl("https://10.0.0.1/a.png"))
        assertNull(LiveStream.pictureUrl("  "))
        assertEquals("https://x.example/a.png", LiveStream.pictureUrl(" https://x.example/a.png "))
    }
}
