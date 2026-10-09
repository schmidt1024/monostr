package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path
import java.time.Duration

class WithdrawnTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val bob = Keys.generate()

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
    private fun deletion(keys: Keys, id: String) =
        EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", id)), Tag.parse(listOf("k", "1")))).signWithKeys(keys)

    @Test
    fun `a note that arrives after its deletion request is never shown`() = runTest {
        val e = engine()
        e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null), Contact(bob.publicKey(), null, null))).signWithKeys(me))
        val withdrawn = EventBuilder.textNote("withdrawn").signWithKeys(alice)
        val kept = EventBuilder.textNote("kept").signWithKeys(alice)
        e.save(deletion(alice, withdrawn.id().toHex())) // the request first ...
        e.save(withdrawn); e.save(kept)                  // ... the note afterwards: the database accepts it
        assertNotNull(e.eventById(withdrawn.id().toHex())) // what this task works around
        val feed = NostrFeedRepository(e)
        assertEquals(listOf("kept"), feed.notes().map { it.content })
        assertEquals(listOf("kept"), feed.notesBy(alice.publicKey().toHex(), fetch = false).map { it.content })
        // a repost by bob embedding the withdrawn note
        e.save(EventBuilder.repost(withdrawn, null).signWithKeys(bob))
        assertTrue(feed.notes().none { it.isRepost })
        // a thread: the withdrawn note as a reply
        val root = EventBuilder.textNote("root").signWithKeys(bob); e.save(root)
        val reply = EventBuilder.textNoteReply("gone reply", root, null, null).signWithKeys(alice)
        e.save(deletion(alice, reply.id().toHex())); e.save(reply)
        assertTrue(NostrThreadRepository(e).observe(root.id().toHex()).first().replies.isEmpty())
        // opening the withdrawn note itself
        assertNull(NostrThreadRepository(e).note(withdrawn.id().toHex()))
        e.close()
    }

    @Test
    fun `a bare repost of a withdrawn note is dropped, a repost of a kept note survives`() = runTest {
        val e = engine()
        e.save(EventBuilder.contactList(listOf(Contact(bob.publicKey(), null, null))).signWithKeys(me))
        val withdrawn = EventBuilder.textNote("withdrawn").signWithKeys(alice)
        val kept = EventBuilder.textNote("kept").signWithKeys(alice)
        e.save(deletion(alice, withdrawn.id().toHex())); e.save(withdrawn); e.save(kept)
        assertNotNull(e.eventById(withdrawn.id().toHex()))
        fun bare(note: rust.nostr.sdk.Event) = EventBuilder(Kind(6u), "")
            .tags(listOf(Tag.parse(listOf("e", note.id().toHex())), Tag.parse(listOf("p", note.author().toHex()))))
            .signWithKeys(bob)
        e.save(bare(withdrawn))
        assertTrue(NostrFeedRepository(e).notes().none { it.isRepost })
        e.save(bare(kept))
        e.save(EventBuilder.repost(kept, null).signWithKeys(bob))
        val reposts = NostrFeedRepository(e).notes().filter { it.isRepost }
        assertEquals(2, reposts.size) // the bare and the embedded repost of the kept note
        e.close()
    }

    @Test
    fun `only the author's request counts`() = runTest {
        val e = engine()
        e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
        val note = EventBuilder.textNote("stays").signWithKeys(alice)
        e.save(deletion(bob, note.id().toHex())); e.save(note)
        assertEquals(listOf("stays"), NostrFeedRepository(e).notes().map { it.content })
        e.close()
    }

    @Test
    fun `a search result that is withdrawn is dropped`() = runTest {
        val withdrawn = EventBuilder.textNote("monero withdrawn").signWithKeys(alice)
        val kept = EventBuilder.textNote("monero kept").signWithKeys(alice)
        SilentWsRelay(serve = listOf(withdrawn.asJson(), kept.asJson())).use { relay ->
            val e = engine()
            e.save(deletion(alice, withdrawn.id().toHex())) // the request first; the note was never stored
            val repo = NostrSearchRepository(e, Duration.ofSeconds(3))
            assertEquals(listOf("monero kept"), repo.notes("monero", listOf(relay.url)).items.map { it.content })
            e.close()
        }
    }

    @Test
    fun `a withdrawn quoted note is missing`() = runTest {
        val e = engine()
        val withdrawn = EventBuilder.textNote("gone").signWithKeys(alice)
        e.save(deletion(alice, withdrawn.id().toHex())); e.save(withdrawn)
        assertNotNull(e.eventById(withdrawn.id().toHex()))
        assertEquals(QuoteResult.Missing, NostrQuoteRepository(e, scope = e.backgroundScope).get(withdrawn.id().toHex()))
        e.close()
    }
}
