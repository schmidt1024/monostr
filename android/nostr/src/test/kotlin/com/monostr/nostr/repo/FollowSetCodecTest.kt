package com.monostr.nostr.repo

import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp

/** Spec perf 3.7: list events are read once, in Kotlin, and remember which event they came from. */
class FollowSetCodecTest {
    private val alice = Keys.generate()
    private val b = Keys.generate().publicKey().toHex()

    private fun list(d: String, title: String, at: ULong) = EventBuilder(Kind(30000u), "")
        .tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", title)), Tag.parse(listOf("p", b))))
        .customCreatedAt(Timestamp.fromSecs(at)).signWithKeys(alice)

    @Test
    fun `a public list is read from the decoded event and carries its event id`() = runTest {
        val ev = list("x", "Picks", 1000u)
        val set = FollowSetCodec.decode(NoteMapper.decoded(ev), null, interactive = false)!!
        assertEquals("Picks", set.title)
        assertEquals(listOf(b), set.members)
        assertEquals(1000L, set.createdAt)
        assertEquals(alice.publicKey().toHex(), set.author)
        assertEquals(ev.id().toHex(), set.eventId)
    }

    @Test
    fun `an empty replacement is no list`() = runTest {
        val ev = EventBuilder(Kind(30000u), "").tags(listOf(Tag.parse(listOf("d", "gone")))).signWithKeys(alice)
        assertNull(FollowSetCodec.decode(NoteMapper.decoded(ev), null, interactive = false))
    }

    @Test
    fun `the newest version of each d is kept`() {
        val old = list("x", "Old", 1000u)
        val new = list("x", "New", 2000u)
        val other = list("y", "Other", 500u)
        val kept = newestPerD(listOf(old, new, other).map { NoteMapper.decoded(it) })
        assertEquals(setOf(new.id().toHex(), other.id().toHex()), kept.map { it.id }.toSet())
    }
}
