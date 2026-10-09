package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path

class DeletionFetchTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val bob = Keys.generate()

    private fun deletion(keys: Keys, id: String): Event =
        EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", id)), Tag.parse(listOf("k", "1")))).signWithKeys(keys)

    private suspend fun engine(relay: SilentWsRelay): NostrEngine {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
        e.connect()
        assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null), Contact(bob.publicKey(), null, null))).signWithKeys(me))
        return e
    }

    @Test
    fun `a refresh asks for the followed authors' deletion requests and the withdrawn note leaves the feed`() = runTest {
        val withdrawn = EventBuilder.textNote("withdrawn").signWithKeys(alice)
        val kept = EventBuilder.textNote("kept").signWithKeys(alice)
        // bob asks to delete alice's note: not his to delete
        SilentWsRelay(serve = listOf(deletion(alice, withdrawn.id().toHex()).asJson(), deletion(bob, kept.id().toHex()).asJson()), answerEose = true).use { relay ->
            val e = engine(relay)
            e.save(withdrawn); e.save(kept)
            val repo = NostrFeedRepository(e)
            assertEquals(2, repo.notes().size)
            repo.refresh()
            assertTrue(relay.requests.any { "\"kinds\":[" in it && "5" in it.substringAfter("\"kinds\":[").substringBefore("]").split(",") }, "kind 5 was not requested")
            assertEquals(listOf("kept"), repo.notes().map { it.content })
            assertEquals(listOf("kept"), repo.notesBy(alice.publicKey().toHex(), fetch = true).map { it.content })
            e.close()
        }
    }

    @Test
    fun `a thread drops a withdrawn reply`() = runTest {
        val root = EventBuilder.textNote("root").signWithKeys(alice)
        val reply = EventBuilder.textNoteReply("reply", root, null, null).signWithKeys(bob)
        SilentWsRelay(serve = listOf(deletion(bob, reply.id().toHex()).asJson()), answerEose = true).use { relay ->
            val e = engine(relay)
            e.save(root); e.save(reply)
            val views = NostrThreadRepository(e).observe(root.id().toHex()).take(2).toList()
            assertEquals(1, views.first().replies.size) // local first
            assertTrue(views.last().replies.isEmpty())
            assertEquals(false, views.last().gone)
            e.close()
        }
    }

    @Test
    fun `a thread reports a withdrawn focused note as gone`() = runTest {
        val root = EventBuilder.textNote("root").signWithKeys(alice)
        SilentWsRelay(serve = listOf(deletion(alice, root.id().toHex()).asJson()), answerEose = true).use { relay ->
            val e = engine(relay)
            e.save(root)
            val views = NostrThreadRepository(e).observe(root.id().toHex()).take(2).toList()
            assertEquals(false, views.first().gone) // local first
            assertEquals(true, views.last().gone)
            e.close()
        }
    }

    @Test
    fun `a repost embedding a withdrawn note is not shown, a repost of a kept note is`() = runTest {
        val withdrawn = EventBuilder.textNote("withdrawn").signWithKeys(alice)
        val kept = EventBuilder.textNote("kept").signWithKeys(alice)
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            e.save(withdrawn); e.save(kept)
            e.save(deletion(alice, withdrawn.id().toHex()))
            // reposts carry the original in their content: that copy must not bring it back
            e.save(EventBuilder.repost(withdrawn, null).signWithKeys(bob))
            e.save(EventBuilder.repost(kept, null).signWithKeys(bob))
            val repo = NostrFeedRepository(e)
            val reposted = repo.notes().mapNotNull { it.repostOf?.content }
            assertEquals(listOf("kept"), reposted)
            assertEquals(listOf("kept"), repo.notesBy(bob.publicKey().toHex(), fetch = false).mapNotNull { it.repostOf?.content })
            e.close()
        }
    }

    @Test
    fun `deletion requests are fetched apart from the notes, so a page of requests does not crowd out the notes`() = runTest {
        val notes = (1..3).map { EventBuilder.textNote("note $it").signWithKeys(alice) }
        val requests = (1..60).map { deletion(alice, "%064x".format(it)) }
        SilentWsRelay(serve = (notes + requests).map { it.asJson() }, answerEose = true).use { relay ->
            val e = engine(relay)
            val repo = NostrFeedRepository(e)
            repo.refresh(limit = 50)
            val kinds = relay.requests.map { it.substringAfter("\"kinds\":[").substringBefore("]").split(",").map(String::trim).toSet() }
            assertTrue(setOf("1", "6") in kinds, "no REQ for exactly kinds 1 and 6: ${relay.requests}")
            assertTrue(setOf("5") in kinds, "no REQ for exactly kind 5: ${relay.requests}")
            assertEquals(setOf("note 1", "note 2", "note 3"), repo.notes().map { it.content }.toSet())
            e.close()
        }
    }
}
