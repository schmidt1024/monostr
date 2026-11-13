package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.untilFirst
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Task 4 of the polls plan: a kind 1068 shows wherever a kind 1 note does, and replies and reposts name kind 1068. */
class PollsInRepositoriesTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")
    private val author = alice.publicKey().toHex()
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
    private suspend fun tags(e: NostrEngine, id: String) = e.eventById(id)!!.tags().toVec().map { it.asVec() }

    private fun pollEvent(at: Long = 1500): Event =
        EventBuilder(Kind(1068u), "Lunch?")
            .tags(listOf(listOf("option", "a", "Yes"), listOf("option", "b", "No")).map { Tag.parse(it) })
            .customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(alice)

    private suspend fun savedPoll(e: NostrEngine, at: Long = 1500): Note {
        val ev = pollEvent(at)
        e.save(ev)
        return NoteMapper.note(ev)!!
    }

    private fun contacts() = EventBuilder(Kind(3u), "").tags(listOf(Tag.parse(listOf("p", author)))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(me)

    @Test
    fun `a followed author's poll shows in the home feed`() = runTest {
        val e = engine()
        e.save(contacts())
        val ev = pollEvent()
        e.save(ev)
        val notes = NostrFeedRepository(e).notes()
        assertEquals(listOf(ev.id().toHex()), notes.map { it.id })
        assertNotNull(notes.single().poll)
        e.close()
    }

    @Test
    fun `a poll shows in the profile posts but not in the replies`() = runTest {
        val e = engine()
        val ev = pollEvent()
        e.save(ev)
        val repo = NostrFeedRepository(e)
        assertEquals(listOf(ev.id().toHex()), repo.profileNotes(author, ProfileSection.POSTS, 20, fetch = false).map { it.id })
        assertTrue(repo.profileNotes(author, ProfileSection.REPLIES, 20, fetch = false).isEmpty())
        e.close()
    }

    private fun deletionOf(ev: Event, by: Keys = alice) =
        EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", ev.id().toHex())), Tag.parse(listOf("k", "1068")))).customCreatedAt(Timestamp.fromSecs(1600u)).signWithKeys(by)

    @Test
    fun `a poll that arrives after its author's deletion request stays out of the feed and the profile posts`() = runTest {
        val e = engine()
        e.save(contacts())
        val ev = pollEvent()
        val repo = NostrFeedRepository(e)
        // control: without the request the same poll is shown
        e.save(pollEvent(at = 1400))
        assertEquals(1, repo.notes().size)
        assertEquals(1, repo.profileNotes(author, ProfileSection.POSTS, 20, fetch = false).size)
        // the request is stored first, the poll after it (a relay without NIP-09, a late fetch)
        e.save(deletionOf(ev))
        e.save(ev)
        assertEquals(listOf(pollEvent(at = 1400).id().toHex()), repo.notes().map { it.id })
        assertEquals(listOf(pollEvent(at = 1400).id().toHex()), repo.profileNotes(author, ProfileSection.POSTS, 20, fetch = false).map { it.id })
        e.close()
    }

    @Test
    fun `Withdrawn hides a poll named by its author's kind 5 but not by someone else's`() = runTest {
        val e = engine()
        val ev = pollEvent()
        assertTrue(!Withdrawn.isWithdrawn(e, ev), "control: no request yet")
        e.save(deletionOf(ev, by = me))
        assertTrue(!Withdrawn.isWithdrawn(e, ev), "a request by another key does not count")
        e.save(deletionOf(ev))
        assertTrue(Withdrawn.isWithdrawn(e, ev))
        e.close()
    }

    @Test
    fun `a poll's thread shows kind 1 and kind 1111 replies`() = runTest {
        val e = engine()
        val poll = pollEvent()
        e.save(poll)
        val comment = EventBuilder(Kind(1111u), "comment").tags(listOf(
            listOf("E", poll.id().toHex()), listOf("K", "1068"), listOf("P", author),
            listOf("e", poll.id().toHex()), listOf("k", "1068"), listOf("p", author),
        ).map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(1600u)).signWithKeys(me)
        val note = EventBuilder.textNoteReply("note reply", poll, poll, null).customCreatedAt(Timestamp.fromSecs(1700u)).signWithKeys(me)
        e.save(comment); e.save(note)
        val view = NostrThreadRepository(e).observe(poll.id().toHex()).untilFirst { it.replies.size == 2 }.last()
        assertEquals(setOf(comment.id().toHex(), note.id().toHex()), view.replies.map { it.id }.toSet())
        e.close()
    }

    @Test
    fun `a reply to a poll is a 1111 with K and k 1068`() = runTest {
        val e = engine(); val repo = NostrPublishRepository(e); val poll = savedPoll(e)
        val top = repo.comment("my take", poll)
        assertEquals(1111, e.eventById(top.eventId)!!.kind().asU16().toInt())
        assertEquals(
            listOf(listOf("E", poll.id), listOf("K", "1068"), listOf("P", author), listOf("e", poll.id), listOf("k", "1068"), listOf("p", author)),
            tags(e, top.eventId),
        )
        e.close()
    }

    @Test
    fun `a reply to a comment under a poll keeps the poll as root`() = runTest {
        val e = engine(); val repo = NostrPublishRepository(e); val poll = savedPoll(e)
        val theirs = EventBuilder(Kind(1111u), "hm").tags(listOf(Tag.parse(listOf("E", poll.id)), Tag.parse(listOf("K", "1068")))).signWithKeys(alice)
        e.save(theirs)
        val parent = NoteMapper.note(theirs)!!
        val reply = repo.comment("thanks", poll, parent)
        assertEquals(
            listOf(listOf("E", poll.id), listOf("K", "1068"), listOf("P", author), listOf("e", parent.id), listOf("k", "1111"), listOf("p", author)),
            tags(e, reply.eventId),
        )
        e.close()
    }

    @Test
    fun `a comment under a poll names the poll as its root`() = runTest {
        val e = engine(); val repo = NostrPublishRepository(e); val poll = savedPoll(e)
        val top = NoteMapper.note(e.eventById(repo.comment("a", poll).eventId)!!)!!
        assertEquals(poll.id, top.pollRootId)
        assertEquals(null, poll.pollRootId)
        val plain = EventBuilder(Kind(1111u), "x").tags(listOf(Tag.parse(listOf("E", poll.id)), Tag.parse(listOf("K", "30023")))).signWithKeys(alice)
        assertEquals(null, NoteMapper.note(plain)!!.pollRootId)
        e.close()
    }

    @Test
    fun `a comment needs an article or a poll`() = runTest {
        val e = engine(); val repo = NostrPublishRepository(e)
        val ev = EventBuilder.textNote("plain").signWithKeys(alice)
        e.save(ev)
        val plain = NoteMapper.note(ev)!!
        val failed = runCatching { repo.comment("x", plain) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
        e.close()
    }

    @Test
    fun `a repost of a poll is kind 16 with k 1068`() = runTest {
        val e = engine(); val repo = NostrPublishRepository(e); val poll = savedPoll(e)
        val rp = repo.repost(poll)
        val ev = e.eventById(rp.eventId)!!
        assertEquals(16, ev.kind().asU16().toInt())
        assertEquals("", ev.content())
        assertEquals(listOf(listOf("e", poll.id), listOf("p", author), listOf("k", "1068")), tags(e, rp.eventId))
        e.close()
    }

    @Test
    fun `a deletion request for an own poll names k 1068`() = runTest {
        com.monostr.nostr.SilentWsRelay(acceptEvents = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
            e.connect()
            assertTrue(com.monostr.nostr.awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val ev = EventBuilder(Kind(1068u), "Mine?").tags(listOf(listOf("option", "a", "Yes"), listOf("option", "b", "No")).map { Tag.parse(it) }).signWithKeys(me)
            e.save(ev)
            val r = NostrPublishRepository(e).delete(NoteMapper.note(ev)!!)
            assertTrue(r.sentToAny)
            val sent = relay.events.single { it.contains("\"kind\":5") }.replace(" ", "")
            assertTrue(sent.contains("[\"k\",\"1068\"]"), sent)
            e.close()
        }
    }

    @Test
    fun `pollTags names the relay hint on the e tag`() {
        val ev = pollEvent()
        val n = NoteMapper.note(ev)!!
        assertEquals(
            listOf(listOf("e", n.id, "wss://r.example"), listOf("p", author), listOf("k", "1068")),
            pollTags(n, "wss://r.example"),
        )
    }

    @Test
    fun `a quoted poll resolves`() = runTest {
        val e = engine()
        val ev = pollEvent()
        e.save(ev)
        val found = NostrQuoteRepository(e, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)).get(ev.id().toHex()) as QuoteResult.Found
        assertNotNull(found.note.poll)
        e.close()
    }

    @Test
    fun `a mention in a poll is a notification`() = runTest {
        val e = engine()
        val ev = EventBuilder(Kind(1068u), "for you")
            .tags(listOf(listOf("option", "a", "Yes"), listOf("option", "b", "No"), listOf("p", me.publicKey().toHex())).map { Tag.parse(it) })
            .signWithKeys(alice)
        e.save(ev)
        val items = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }.list()
        assertEquals(listOf(NotificationKind.MENTION), items.map { it.kind })
        assertTrue(LiveMatch.matches(com.monostr.nostr.model.NoteMapper.decoded(ev), me.publicKey().toHex()))
        e.close()
    }
}
