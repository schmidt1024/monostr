package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.Article
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path

class PublishArticleTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")
    private val author = alice.publicKey().toHex()
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
    private suspend fun tags(e: NostrEngine, id: String) = e.eventById(id)!!.tags().toVec().map { it.asVec() }
    private val coord = "30023:$author:post"

    private suspend fun article(e: NostrEngine): com.monostr.nostr.model.Note {
        val ev = EventBuilder(Kind(30023u), "# Hi").tags(listOf(Tag.parse(listOf("d", "post")))).signWithKeys(alice)
        e.save(ev)
        return Article.parse(AddressableEvent(ev.id().toHex(), 30023, author, "post", ev.createdAt().asSecs().toLong(), listOf(listOf("d", "post")), "# Hi"))!!.asNote()
    }

    @Test
    fun `like and repost of an article name the coordinate`() = runTest {
        val e = engine(); val repo = NostrPublishRepository(e); val a = article(e)
        val expected = listOf(listOf("e", a.id), listOf("a", coord), listOf("p", author), listOf("k", "30023"))
        val like = repo.like(a)
        assertEquals(7, e.eventById(like.eventId)!!.kind().asU16().toInt())
        assertEquals(expected, tags(e, like.eventId))
        val rp = repo.repost(a)
        val ev = e.eventById(rp.eventId)!!
        assertEquals(16, ev.kind().asU16().toInt())
        assertEquals(expected, tags(e, rp.eventId))
        // final review I2: a long article would break relay size limits, so the repost carries no copy of it
        assertEquals("", ev.content())
        e.close()
    }

    @Test
    fun `a relay hint goes on the e and a tags`() = runTest {
        val e = engine(); val a = article(e)
        assertEquals(
            listOf(listOf("e", a.id, "wss://r.example"), listOf("a", coord, "wss://r.example"), listOf("p", author), listOf("k", "30023")),
            articleTags(a, "wss://r.example"),
        )
        e.close()
    }

    @Test
    fun `a reply to an old-style kind 1 reply under the article names kind 1 as the parent kind`() = runTest {
        val e = engine(); val repo = NostrPublishRepository(e); val a = article(e)
        val old = EventBuilder(Kind(1u), "old").tags(listOf(Tag.parse(listOf("a", coord)), Tag.parse(listOf("e", a.id)))).signWithKeys(alice)
        e.save(old)
        val parent = NoteMapper.note(old)!!
        val reply = repo.comment("re", a, parent)
        assertEquals(
            listOf(listOf("A", coord), listOf("E", a.id), listOf("K", "30023"), listOf("P", author), listOf("e", parent.id), listOf("k", "1"), listOf("p", author)),
            tags(e, reply.eventId),
        )
        e.close()
    }

    @Test
    fun `top level comment and comment reply tags`() = runTest {
        val e = engine(); val repo = NostrPublishRepository(e); val a = article(e)
        val top = repo.comment("nice", a)
        assertEquals(1111, e.eventById(top.eventId)!!.kind().asU16().toInt())
        assertEquals(
            listOf(listOf("A", coord), listOf("E", a.id), listOf("K", "30023"), listOf("P", author), listOf("a", coord), listOf("e", a.id), listOf("k", "30023"), listOf("p", author)),
            tags(e, top.eventId),
        )
        // a comment by someone else (rust-nostr drops a p tag naming the signer, as for every reply)
        val theirs = EventBuilder(Kind(1111u), "hm").tags(listOf(Tag.parse(listOf("A", coord)), Tag.parse(listOf("E", a.id)))).signWithKeys(alice)
        e.save(theirs)
        val parent = NoteMapper.note(theirs)!!
        val reply = repo.comment("thanks", a, parent)
        assertEquals(
            listOf(listOf("A", coord), listOf("E", a.id), listOf("K", "30023"), listOf("P", author), listOf("e", parent.id), listOf("k", "1111"), listOf("p", author)),
            tags(e, reply.eventId),
        )
        e.close()
    }
}
