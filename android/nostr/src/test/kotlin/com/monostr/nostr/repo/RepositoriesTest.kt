package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

class RepositoriesTest {
    @TempDir lateinit var dir: Path

    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val bob = Keys.generate()

    private suspend fun engine(): NostrEngine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())

    private fun note(keys: Keys, text: String, at: Long, tags: List<Tag> = emptyList()): Event =
        EventBuilder.textNote(text).tags(tags).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    @Test
    fun `feed shows followed authors only, newest first, with reposts`() = runTest {
        val e = engine()
        e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
        val a1 = note(alice, "alice 1", 1000); e.save(a1)
        e.save(note(alice, "alice 2", 2000))
        e.save(note(bob, "bob, not followed", 3000))
        e.save(note(me, "mine", 1500))
        e.save(EventBuilder.repost(a1, null).customCreatedAt(Timestamp.fromSecs(2500u)).signWithKeys(alice))
        val repo = NostrFeedRepository(e)
        assertEquals(listOf(alice.publicKey().toHex(), me.publicKey().toHex()), repo.follows())
        val notes = repo.notes()
        assertEquals(listOf(2500L, 2000L, 1500L, 1000L), notes.map { it.createdAt })
        assertTrue(notes[0].isRepost); assertEquals("alice 1", notes[0].repostOf?.content)
        assertEquals("mine", notes[2].content)
        assertEquals(listOf(2000L, 1500L), repo.notes(limit = 2, until = 2400).map { it.createdAt })
        assertEquals(notes, repo.live().first())
        assertEquals(listOf("alice 2", "alice 1"), repo.notesBy(alice.publicKey().toHex(), fetch = false).filter { !it.isRepost }.map { it.content })
    }

    @Test
    fun `profile repository reads kind 0 from the database and falls back to empty`() = runTest {
        val e = engine()
        val meta = Metadata.fromJson("""{"name":"alice","display_name":"Alice A","picture":"https://x/a.png","about":"hi","nip05":"alice@x"}""")
        e.save(EventBuilder.metadata(meta).signWithKeys(alice))
        val repo = NostrProfileRepository(e, now = { 1_000_000 })
        val p = repo.get(alice.publicKey().toHex())
        assertEquals("Alice A", p.displayName); assertEquals("alice", p.name); assertEquals("https://x/a.png", p.picture); assertEquals("alice@x", p.nip05)
        assertEquals("Alice A", repo.observe(alice.publicKey().toHex()).first().shownName)
        val unknown = repo.get(bob.publicKey().toHex())
        assertNull(unknown.name); assertEquals(bob.publicKey().toHex(), unknown.pubkey)
        repo.prefetch(listOf(alice.publicKey().toHex(), bob.publicKey().toHex()))
        assertEquals("Alice A", repo.observe(alice.publicKey().toHex()).first().displayName)
        assertEquals(1_000_000L, repo.lastFetchAt(alice.publicKey().toHex()))
        assertEquals(1_000_000L, repo.lastFetchAt(bob.publicKey().toHex()))
    }

    @Test
    fun `a missing profile is not fetched again within the retry window`() = runTest {
        val e = engine()
        var time = 1_000_000L
        val repo = NostrProfileRepository(e, now = { time })
        val pk = bob.publicKey().toHex()
        repo.prefetch(listOf(pk))
        assertEquals(1_000_000L, repo.lastFetchAt(pk))
        time += 100
        repo.prefetch(listOf(pk)); repo.get(pk)
        assertEquals(1_000_000L, repo.lastFetchAt(pk)) // no second relay round trip
        time += 600
        repo.get(pk)
        assertEquals(1_000_700L, repo.lastFetchAt(pk))
    }

    @Test
    fun `a profile present in the database is not fetched again after prefetch`() = runTest {
        val e = engine()
        e.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice"}""")).signWithKeys(alice))
        var time = 1_000_000L
        val repo = NostrProfileRepository(e, now = { time })
        val pk = alice.publicKey().toHex()
        repo.prefetch(listOf(pk))
        assertEquals(1_000_000L, repo.lastFetchAt(pk))
        time = 1_000_100L
        assertEquals("alice", repo.get(pk).name)
        assertEquals(1_000_000L, repo.lastFetchAt(pk)) // get() stamps when it fetches; no relay round trip here
    }

    @Test
    fun `thread collects replies of the root oldest first`() = runTest {
        val e = engine()
        val root = note(alice, "root", 1000); e.save(root)
        val r1 = EventBuilder.textNoteReply("first", root, null, null).customCreatedAt(Timestamp.fromSecs(1100u)).signWithKeys(bob); e.save(r1)
        val r2 = EventBuilder.textNoteReply("second", r1, root, null).customCreatedAt(Timestamp.fromSecs(1200u)).signWithKeys(alice); e.save(r2)
        e.save(note(bob, "unrelated", 1300))
        val view = NostrThreadRepository(e).observe(r1.id().toHex()).first()
        assertEquals(root.id().toHex(), view.root?.id)
        assertEquals("first", view.focused.content)
        assertEquals(listOf("first", "second"), view.replies.map { it.content })
        assertEquals(root.id().toHex(), view.replies[1].rootId)
        assertEquals(r1.id().toHex(), view.replies[1].replyToId)
        assertNull(NostrThreadRepository(e).observe("f".repeat(64)).firstOrNull())
    }

    @Test
    fun `thread repository returns a single note from the database or null`() = runTest {
        val e = engine()
        val n = note(alice, "https://x.example/a.jpg", 1000); e.save(n)
        val repo = NostrThreadRepository(e)
        assertEquals(1, repo.note(n.id().toHex())!!.media.size)
        assertNull(repo.note("f".repeat(64)))
    }

    @Test
    fun `publish signs with the engine signer, stores locally and reports no relays`() = runTest {
        val e = engine()
        val repo = NostrPublishRepository(e)
        val posted = repo.post("  hello  ")
        val postedEvent = e.eventById(posted.eventId)!!
        assertEquals(me.publicKey().toHex(), postedEvent.author().toHex())
        assertEquals("hello", postedEvent.content())
        assertTrue(postedEvent.verify())
        assertEquals(1, posted.kind)
        assertFalse(posted.sentToAny)
        val parent = NoteMapper.note(postedEvent)!!
        val reply = repo.reply("re", parent)
        val replyEvent = e.eventById(reply.eventId)!!
        val eTags = replyEvent.tags().toVec().map { it.asVec() }.filter { it[0] == "e" }
        assertEquals(1, eTags.size, "tags: $eTags")
        assertEquals(parent.id, eTags[0][1], "tags: $eTags")
        assertEquals("root", eTags[0].getOrNull(3), "tags: $eTags")
        val replyNote = NoteMapper.note(replyEvent)!!
        assertEquals(parent.id, replyNote.rootId); assertEquals(parent.id, replyNote.replyToId)
        // reply to a reply: root and reply markers point at different notes
        val second = repo.reply("re re", replyNote)
        val tags2 = e.eventById(second.eventId)!!.tags().toVec().map { it.asVec() }.filter { it[0] == "e" }
        assertEquals(parent.id, tags2.first { it.getOrNull(3) == "root" }[1], "tags: $tags2")
        assertEquals(replyNote.id, tags2.first { it.getOrNull(3) == "reply" }[1], "tags: $tags2")
        val like = repo.like(parent)
        assertEquals(7, like.kind); assertEquals("+", e.eventById(like.eventId)!!.content())
        val repost = repo.repost(parent)
        assertEquals(6, repost.kind)
        assertEquals(parent.id, NoteMapper.note(e.eventById(repost.eventId)!!)!!.repostOf?.id)
    }

    @Test
    fun `note mapper handles kind 6 with only an e tag`() = runTest {
        val e = engine()
        val original = note(alice, "orig", 100); e.save(original)
        val bare = EventBuilder(rust.nostr.sdk.Kind(6u), "").tags(listOf(Tag.event(original.id()), Tag.publicKey(alice.publicKey()))).signWithKeys(bob)
        val mapped = NoteMapper.note(bare) { id -> kotlinx.coroutines.runBlocking { e.eventById(id) } }
        assertEquals("orig", mapped?.repostOf?.content)
        assertNull(NoteMapper.note(EventBuilder(rust.nostr.sdk.Kind(30023u), "x").signWithKeys(bob)))
    }

    @Test
    fun `a tampered embedded repost is never shown`() = runTest {
        val e = engine()
        val original = note(alice, "orig", 100); e.save(original)
        val tampered = original.asJson().replace("\"orig\"", "\"forged\"")
        assertTrue(tampered.contains("forged"))
        // no e tag: nothing to fall back to
        val bare = EventBuilder(rust.nostr.sdk.Kind(6u), tampered).signWithKeys(bob)
        assertNull(NoteMapper.note(bare) { id -> kotlinx.coroutines.runBlocking { e.eventById(id) } }?.repostOf)
        // e tag present: the stored original is shown, never the tampered content
        val tagged = EventBuilder(rust.nostr.sdk.Kind(6u), tampered).tags(listOf(Tag.event(original.id()))).signWithKeys(bob)
        assertEquals("orig", NoteMapper.note(tagged) { id -> kotlinx.coroutines.runBlocking { e.eventById(id) } }?.repostOf?.content)
    }

    @Test
    fun `an embedded note whose id differs from the e tag is ignored`() = runTest {
        val embedded = note(alice, "embedded", 100)
        val other = note(alice, "other", 200)
        val mismatched = EventBuilder(rust.nostr.sdk.Kind(6u), embedded.asJson()).tags(listOf(Tag.event(other.id()))).signWithKeys(bob)
        assertNull(NoteMapper.note(mismatched) { null }?.repostOf)
        assertEquals("other", NoteMapper.note(mismatched) { id -> other.takeIf { it.id().toHex() == id } }?.repostOf?.content)
    }

    @Test
    fun `a reposted note loaded from the feed can be liked and reposted`() = runTest {
        val e = engine()
        e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
        val foreign = note(bob, "not stored", 100) // only known through alice's repost
        e.save(EventBuilder.repost(foreign, null).customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(alice))
        val original = NostrFeedRepository(e).notes().single().repostOf!!
        assertEquals("not stored", original.content)
        val publish = NostrPublishRepository(e)
        assertEquals(7, publish.like(original).kind)
        assertEquals(6, publish.repost(original).kind)
    }

    @Test
    fun `relay list publishes kind 10002 with one r tag per relay`() = runTest {
        val e = engine()
        val result = NostrPublishRepository(e).relayList(listOf("wss://a.example", "wss://b.example/"))
        assertEquals(10002, result.kind)
        val tags = e.eventById(result.eventId)!!.tags().toVec().map { it.asVec() }
        assertEquals(listOf(listOf("r", "wss://a.example"), listOf("r", "wss://b.example")), tags)
    }

    @Test
    fun `local profiles come from the database only and skip unknown keys`() = runTest {
        val e = engine()
        e.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice"}""")).signWithKeys(alice))
        val repo = NostrProfileRepository(e, now = { 1_000_000 })
        val list = repo.local(listOf(alice.publicKey().toHex(), bob.publicKey().toHex()))
        assertEquals(listOf("alice"), list.map { it.name })
        assertNull(repo.lastFetchAt(bob.publicKey().toHex())) // no relay fetch was attempted
        e.close()
    }

    @Test
    fun `local follows read the stored contact list only`() = runTest {
        val e = engine()
        val repo = NostrFeedRepository(e)
        assertEquals(listOf(me.publicKey().toHex()), repo.followsLocal())
        e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
        assertEquals(listOf(alice.publicKey().toHex(), me.publicKey().toHex()), repo.followsLocal())
        e.close()
    }
}
