package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Nip19Event
import java.nio.file.Path

class PublishQuoteTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")
    private val carol = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")
    private suspend fun engine(relays: List<String> = emptyList()) =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), relays)
    private suspend fun tags(e: NostrEngine, id: String) = e.eventById(id)!!.tags().toVec().map { it.asVec() }
    private fun quoted() = NoteMapper.note(EventBuilder.textNote("original").signWithKeys(alice))!!

    @Test
    fun `post and reply carry one p tag per mentioned person`() = runTest {
        val e = engine()
        val repo = NostrPublishRepository(e)
        val c = "nostr:" + carol.publicKey().toBech32()
        val a = "nostr:" + alice.publicKey().toBech32()
        val post = repo.post("hi $c and $a and again $c")
        val postP = tags(e, post.eventId).filter { it[0] == "p" }.map { it[1] }
        assertEquals(listOf(alice.publicKey().toHex(), carol.publicKey().toHex()).sorted(), postP.sorted())
        val parent = EventBuilder.textNote("parent").signWithKeys(alice)
        e.save(parent)
        val reply = repo.reply("thanks $c", NoteMapper.note(parent)!!)
        val replyP = tags(e, reply.eventId).filter { it[0] == "p" }.map { it[1] }
        assertEquals(1, replyP.count { it == carol.publicKey().toHex() })
        assertEquals(1, replyP.count { it == alice.publicKey().toHex() })
        e.close()
    }

    @Test
    fun `a reply keeps the p tag of a person the parent already tagged, once`() = runTest {
        val e = engine()
        val repo = NostrPublishRepository(e)
        val c = "nostr:" + carol.publicKey().toBech32()
        val parent = EventBuilder.textNote("parent").tags(listOf(rust.nostr.sdk.Tag.parse(listOf("p", carol.publicKey().toHex())))).signWithKeys(alice)
        e.save(parent)
        val reply = repo.reply("thanks $c", NoteMapper.note(parent)!!)
        val replyP = tags(e, reply.eventId).filter { it[0] == "p" }.map { it[1] }
        assertEquals(1, replyP.count { it == carol.publicKey().toHex() })
        assertEquals(1, replyP.count { it == alice.publicKey().toHex() })
        // carol is the parent's author and mentioned: still exactly one p tag
        val own = EventBuilder.textNote("from carol").signWithKeys(carol)
        e.save(own)
        val reply2 = repo.reply("hi $c", NoteMapper.note(own)!!)
        assertEquals(1, tags(e, reply2.eventId).count { it[0] == "p" && it[1] == carol.publicKey().toHex() })
        e.close()
    }

    @Test
    fun `a quote ends with the nevent, tags q and p, and allows an empty text`() = runTest {
        val e = engine()
        val repo = NostrPublishRepository(e)
        val of = quoted()
        val r = repo.quote("  my take  ", of)
        val content = e.eventById(r.eventId)!!.content()
        assertTrue(content.startsWith("my take\n\nnostr:nevent1"))
        val nevent = Nip19Event.fromNostrUri(content.substringAfterLast("\n"))
        assertEquals(of.id, nevent.eventId().toHex())
        assertEquals(of.author, nevent.author()?.toHex())
        assertTrue(nevent.relays().isEmpty()) // no connected relay: no hint
        assertEquals(listOf(listOf("q", of.id, "", of.author), listOf("p", of.author)), tags(e, r.eventId))
        val bare = repo.quote("", of)
        assertTrue(e.eventById(bare.eventId)!!.content().startsWith("nostr:nevent1"))
        e.close()
    }

    @Test
    fun `the relay hint is the first connected normal relay`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val e = engine(listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val r = NostrPublishRepository(e).quote("x", quoted())
            assertTrue(r.sentToAny)
            assertEquals(relay.url, tags(e, r.eventId).first { it[0] == "q" }[2])
            val nevent = Nip19Event.fromNostrUri(e.eventById(r.eventId)!!.content().substringAfterLast("\n"))
            assertEquals(listOf(relay.url), nevent.relays().map { it.toString().trimEnd('/') })
            e.close()
        }
    }
}
