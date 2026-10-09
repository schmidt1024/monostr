package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteAttachment
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import java.nio.file.Path

class PublishAttachmentsTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
    private suspend fun tags(e: NostrEngine, id: String) = e.eventById(id)!!.tags().toVec().map { it.asVec() }
    private val pic = NoteAttachment("https://media.monostr.com/${"a".repeat(64)}.jpg", "image/jpeg", "a".repeat(64), 1234, 800, 600, "LNM}7u}qfQ}q")
    private val imeta = listOf("imeta", "url ${pic.url}", "m image/jpeg", "x ${"a".repeat(64)}", "size 1234", "dim 800x600", "blurhash LNM}7u}qfQ}q")

    @Test
    fun `a post with a picture carries the url at the end and its imeta tag`() = runTest {
        val e = engine()
        val repo = NostrPublishRepository(e)
        val r = repo.post(" hello ", listOf(pic), sensitive = true)
        assertEquals("hello\n\n${pic.url}", e.eventById(r.eventId)!!.content())
        val t = tags(e, r.eventId)
        assertTrue(imeta in t)
        assertTrue(listOf("content-warning", "") in t)
        // without attachments nothing changes
        val plain = repo.post("hello")
        assertEquals("hello", e.eventById(plain.eventId)!!.content())
        assertTrue(tags(e, plain.eventId).none { it[0] == "imeta" || it[0] == "content-warning" })
        e.close()
    }

    @Test
    fun `a post of pictures only is allowed`() = runTest {
        val e = engine()
        val r = NostrPublishRepository(e).post("", listOf(pic))
        assertEquals(pic.url, e.eventById(r.eventId)!!.content())
        e.close()
    }

    @Test
    fun `a reply keeps its e and p tags next to the imeta tag`() = runTest {
        val e = engine()
        val parent = EventBuilder.textNote("parent").signWithKeys(alice)
        e.save(parent)
        val r = NostrPublishRepository(e).reply("see", NoteMapper.note(parent)!!, listOf(pic))
        assertEquals("see\n\n${pic.url}", e.eventById(r.eventId)!!.content())
        val t = tags(e, r.eventId)
        assertTrue(imeta in t)
        assertTrue(t.any { it[0] == "e" && it[1] == parent.id().toHex() })
        assertTrue(t.any { it[0] == "p" && it[1] == alice.publicKey().toHex() })
        e.close()
    }

    @Test
    fun `a quote puts the pictures between the text and the nevent`() = runTest {
        val e = engine()
        val of = NoteMapper.note(EventBuilder.textNote("original").signWithKeys(alice))!!
        val r = NostrPublishRepository(e).quote("my take", of, listOf(pic))
        val content = e.eventById(r.eventId)!!.content()
        assertTrue(content.startsWith("my take\n\n${pic.url}\n\nnostr:nevent1"), content)
        val t = tags(e, r.eventId)
        assertTrue(imeta in t)
        assertTrue(t.any { it[0] == "q" && it[1] == of.id })
        // a quote of pictures only: pictures, blank line, nevent
        val bare = NostrPublishRepository(e).quote("", of, listOf(pic))
        assertTrue(e.eventById(bare.eventId)!!.content().startsWith("${pic.url}\n\nnostr:nevent1"))
        e.close()
    }
}
