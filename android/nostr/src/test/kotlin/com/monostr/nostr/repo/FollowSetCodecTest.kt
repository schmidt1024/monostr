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

    /** An old client kept "last opened" timestamps as kind 30000 (seen on a real account, 2023): only `d`, plain content. */
    private fun lastOpened(d: String, content: String) = EventBuilder(Kind(30000u), content)
        .tags(listOf(Tag.parse(listOf("d", d)))).signWithKeys(alice)

    @Test
    fun `an own event with only d and plain content is no list, and nothing is decrypted`() = runTest {
        var decrypts = 0
        val signer = object : com.monostr.nostr.Signer by com.monostr.nostr.LocalSigner(alice.secretKey().toHex()) {
            override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? { decrypts++; return null }
            override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String { decrypts++; throw IllegalStateException() }
        }
        val own = PrivateTags(signer)
        for (ev in listOf(lastOpened("notifications/lastOpened", "1692622489"), lastOpened("chats/null/lastOpened", "1677505371"))) {
            assertNull(FollowSetCodec.decode(NoteMapper.decoded(ev), own, interactive = false))
        }
        assertEquals(0, decrypts)
    }

    @Test
    fun `an own private list with only d is still read, and locked when it cannot be decrypted`() = runTest {
        val signer = com.monostr.nostr.LocalSigner(alice.secretKey().toHex())
        val content = PrivateTags(signer).encrypt(listOf(listOf("title", "Friends"), listOf("p", b)))
        val ev = lastOpened("abc", content)
        val set = FollowSetCodec.decode(NoteMapper.decoded(ev), PrivateTags(signer), interactive = false)!!
        assertEquals("Friends", set.title); assertEquals(listOf(b), set.members); assertEquals(true, set.private)
        val silent = object : com.monostr.nostr.Signer by signer {
            override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
        }
        assertEquals(true, FollowSetCodec.decode(NoteMapper.decoded(ev), PrivateTags(silent), interactive = false)!!.locked)
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
