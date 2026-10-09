package com.monostr.nostr.repo

import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.RustConvert
import com.monostr.tips.PaymentInfo
import com.monostr.tips.TipReceipt
import com.monostr.tips.TipType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

class NotificationsRepositoryTest {
    @TempDir lateinit var dir: Path

    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val bob = Keys.generate()
    private val watcher = Keys.generate()
    private val otherWatcher = Keys.generate()

    @Test
    fun `lists replies, mentions, reactions and valid tips newest first, never own events`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val meHex = me.publicKey().toHex()
        val mine = EventBuilder.textNote("my note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(mine)
        // reply (kind 1 with e + p), mention (kind 1 with p only), reaction, receipts
        e.save(EventBuilder.textNoteReply("reply", mine, null, null).customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(alice))
        e.save(EventBuilder.textNote("hey nostr:$meHex").tags(listOf(Tag.publicKey(me.publicKey()))).customCreatedAt(Timestamp.fromSecs(300u)).signWithKeys(bob))
        e.save(EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs(400u)).signWithKeys(alice))
        // my own payment info names `watcher`
        val myAddress = MoneroKeys.generate().address(Network.MAINNET)
        e.signAndSend(PaymentInfo.build(myAddress, "https://watcher.example", watcher.publicKey().toHex(), 50))
        val intentId = "a".repeat(64)
        e.save(RustConvert.toBuilder(TipReceipt.build(mine.id().toHex(), meHex, alice.publicKey().toHex(), 5_000_000_000, intentId, TipType.LIKE, 500)).signWithKeys(watcher))
        e.save(RustConvert.toBuilder(TipReceipt.build(mine.id().toHex(), meHex, bob.publicKey().toHex(), 7_000_000_000, intentId, TipType.LIKE, 600)).signWithKeys(otherWatcher))
        // my own reply to myself is not a notification
        e.save(EventBuilder.textNoteReply("self", mine, null, null).customCreatedAt(Timestamp.fromSecs(700u)).signWithKeys(me))
        val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
        val items = repo.list()
        assertEquals(listOf(NotificationKind.TIP, NotificationKind.REACTION, NotificationKind.MENTION, NotificationKind.REPLY), items.map { it.kind })
        assertEquals(listOf(500L, 400L, 300L, 200L), items.map { it.createdAt })
        val tip = items[0]
        assertEquals(alice.publicKey().toHex(), tip.from)
        assertEquals(5_000_000_000, tip.amount)
        assertEquals(mine.id().toHex(), tip.noteId)
        assertEquals(mine.id().toHex(), items[1].noteId)
        assertEquals(mine.id().toHex(), items[0].aboutId) // the tip is about my note
        assertEquals("hey nostr:$meHex", items[2].text)
        assertEquals("reply", items[3].text)
        assertEquals(items, repo.live().first())
        assertEquals(listOf(500L), repo.list().filter { it.createdAt > 450 }.map { it.createdAt })
    }

    @Test
    fun `profile tips have no note and anonymous tips no sender`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val meHex = me.publicKey().toHex()
        e.signAndSend(PaymentInfo.build(MoneroKeys.generate().address(Network.MAINNET), "https://watcher.example", watcher.publicKey().toHex(), 50))
        e.save(RustConvert.toBuilder(TipReceipt.build(null, meHex, alice.publicKey().toHex(), 5, "a".repeat(64), TipType.TIP, 500)).signWithKeys(watcher))
        e.save(RustConvert.toBuilder(TipReceipt.build(null, meHex, null, 7, "b".repeat(64), TipType.TIP, 600)).signWithKeys(watcher))
        e.save(RustConvert.toBuilder(TipReceipt.build("c".repeat(64), meHex, null, 9, "d".repeat(64), TipType.TIP, 700)).signWithKeys(watcher))
        val items = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }.list()
        assertEquals(listOf(700L, 600L, 500L), items.map { it.createdAt })
        assertNull(items[0].from)
        assertEquals("c".repeat(64), items[0].noteId)
        assertNull(items[1].from)
        assertNull(items[1].noteId)
        assertEquals(7L, items[1].amount)
        assertEquals(alice.publicKey().toHex(), items[2].from)
        assertNull(items[2].noteId)
    }

    @Test
    fun `reposts and quotes are notifications, and every item names the note it is about`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val meHex = me.publicKey().toHex()
        val mine = EventBuilder.textNote("my note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(mine)
        val mineId = mine.id().toHex()
        val reply = EventBuilder.textNoteReply("reply", mine, null, null).customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(alice); e.save(reply)
        e.save(EventBuilder.textNote("hey nostr:$meHex").tags(listOf(Tag.publicKey(me.publicKey()))).customCreatedAt(Timestamp.fromSecs(300u)).signWithKeys(bob))
        e.save(EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs(400u)).signWithKeys(alice))
        // a repost: NIP-18 kind 6 with `e` (a relay-hint `e` first, the original last) and `p` = me
        val repost = EventBuilder.repost(mine, null).customCreatedAt(Timestamp.fromSecs(500u)).signWithKeys(bob); e.save(repost)
        // a quote: kind 1 with `q` = my note and `p` = me, no reply marker
        val quote = EventBuilder.textNote("look nostr:note1xyz").tags(listOf(Tag.parse(listOf("q", mineId)), Tag.publicKey(me.publicKey()))).customCreatedAt(Timestamp.fromSecs(600u)).signWithKeys(alice); e.save(quote)
        // a reply that also carries `q`: a reply wins
        val both = EventBuilder.textNoteReply("reply with quote", mine, null, null).tags(listOf(Tag.parse(listOf("q", "c".repeat(64))))).customCreatedAt(Timestamp.fromSecs(700u)).signWithKeys(bob); e.save(both)
        // a kind 6 without an `e` tag is nothing
        e.save(EventBuilder(Kind(6u), "").tags(listOf(Tag.publicKey(me.publicKey()))).customCreatedAt(Timestamp.fromSecs(800u)).signWithKeys(bob))
        val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
        val items = repo.list()
        assertEquals(listOf(NotificationKind.REPLY, NotificationKind.QUOTE, NotificationKind.REPOST, NotificationKind.REACTION, NotificationKind.MENTION, NotificationKind.REPLY), items.map { it.kind })
        val byId = items.associateBy { it.id }
        assertEquals(mineId, byId[reply.id().toHex()]!!.aboutId)
        assertEquals(reply.id().toHex(), byId[reply.id().toHex()]!!.noteId)
        assertEquals(mineId, byId[quote.id().toHex()]!!.aboutId)
        assertEquals(quote.id().toHex(), byId[quote.id().toHex()]!!.noteId)
        assertEquals(mineId, byId[repost.id().toHex()]!!.aboutId)
        assertEquals(mineId, byId[repost.id().toHex()]!!.noteId)
        assertEquals(mineId, items.first { it.kind == NotificationKind.REACTION }.aboutId)
        assertNull(items.first { it.kind == NotificationKind.MENTION }.aboutId)
        assertEquals(mineId, byId[both.id().toHex()]!!.aboutId)
    }

    @Test
    fun `a withdrawn reply leaves the list`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val mine = EventBuilder.textNote("my note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(mine)
        val kept = EventBuilder.textNoteReply("kept", mine, null, null).customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(alice); e.save(kept)
        val gone = EventBuilder.textNoteReply("gone", mine, null, null).customCreatedAt(Timestamp.fromSecs(300u)).signWithKeys(alice)
        // the request arrives first (a relay without NIP-09 delivers the reply afterwards), so the database keeps the reply
        e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", gone.id().toHex())), Tag.parse(listOf("k", "1")))).customCreatedAt(Timestamp.fromSecs(350u)).signWithKeys(alice))
        e.save(gone)
        val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
        assertEquals(listOf("kept"), repo.list().map { it.text })
    }

    @Test
    fun `notes(ids) reads locally, asks the relays once for the rest, and names withdrawn and missing notes`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val local = EventBuilder.textNote("local").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(local)
            val withdrawn = EventBuilder.textNote("withdrawn").customCreatedAt(Timestamp.fromSecs(110u)).signWithKeys(me)
            e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", withdrawn.id().toHex())), Tag.parse(listOf("k", "1")))).customCreatedAt(Timestamp.fromSecs(120u)).signWithKeys(me))
            e.save(withdrawn)
            val missing = "f".repeat(64)
            val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
            val result = repo.notes(setOf(local.id().toHex(), withdrawn.id().toHex(), missing))
            assertEquals("local", (result[local.id().toHex()] as AboutNote.Found).note.content)
            assertEquals(AboutNote.Withdrawn, result[withdrawn.id().toHex()])
            assertEquals(AboutNote.Missing, result[missing])
            val idRequests = relay.requests.filter { "\"ids\"" in it }
            assertEquals(1, idRequests.size, "one request for the missing ids: ${relay.requests}")
            assertTrue(missing in idRequests.single() && local.id().toHex() !in idRequests.single())
            // a second call asks again only for what is still missing; found and withdrawn come from the memo
            repo.notes(setOf(local.id().toHex(), withdrawn.id().toHex(), missing))
            assertEquals(2, relay.requests.count { "\"ids\"" in it })
            e.close()
        }
    }

    @Test
    fun `own(ids) names the ids that are the user's own stored notes or reposts, nothing else`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val mine = EventBuilder.textNote("my note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(mine)
        val theirs = EventBuilder.textNote("their note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(alice); e.save(theirs)
        val myRepost = EventBuilder.repost(theirs, null).customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(me); e.save(myRepost)
        val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
        val ids = setOf(mine.id().toHex(), theirs.id().toHex(), myRepost.id().toHex(), "9".repeat(64), "bad")
        assertEquals(setOf(mine.id().toHex(), myRepost.id().toHex()), repo.own(ids))
        assertEquals(emptySet<String>(), repo.own(emptySet()))
    }

    @Test
    fun `an un-like and an un-repost leave the list, whichever order the request and the event arrived in`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val mine = EventBuilder.textNote("my note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(mine)
        val like = EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(alice)
        val repost = EventBuilder.repost(mine, null).customCreatedAt(Timestamp.fromSecs(300u)).signWithKeys(bob)
        val kept = EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs(400u)).signWithKeys(bob); e.save(kept)
        // the request before the event (a relay without NIP-09 delivers the like afterwards) …
        e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", like.id().toHex())), Tag.parse(listOf("k", "7")))).customCreatedAt(Timestamp.fromSecs(250u)).signWithKeys(alice))
        e.save(like)
        // … and the event before the request
        e.save(repost)
        e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", repost.id().toHex())), Tag.parse(listOf("k", "6")))).customCreatedAt(Timestamp.fromSecs(350u)).signWithKeys(bob))
        val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
        assertEquals(listOf(kept.id().toHex()), repo.list().map { it.id })
    }

    @Test
    fun `refresh asks the relays for deletion requests naming the notifications it holds`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
            e.connect()
            val mine = EventBuilder.textNote("my note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(mine)
            val like = EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(alice); e.save(like)
            val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
            repo.refresh(null, 100)
            val deletions = relay.requests.filter { it.contains("\"kinds\":[5]") }
            assertEquals(1, deletions.size, relay.requests.toString())
            assertTrue(deletions.single().contains(like.id().toHex()))
            e.close()
        }
    }

    @Test
    fun `a dislike (NIP-25 minus) is no notification`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val mine = EventBuilder.textNote("my note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(mine)
        e.save(EventBuilder.reaction(mine, "-").customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(alice))
        e.save(EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs(300u)).signWithKeys(bob))
        e.save(EventBuilder.reaction(mine, "🔥").customCreatedAt(Timestamp.fromSecs(400u)).signWithKeys(alice))
        val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
        assertEquals(listOf(alice.publicKey().toHex(), bob.publicKey().toHex()), repo.list().map { it.from })
        assertEquals(listOf("🔥", "+"), repo.list().map { it.text })
    }

    @Test
    fun `a malformed e tag yields no item, and notes() answers Missing for a malformed id instead of throwing`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val mine = EventBuilder.textNote("my note").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(me); e.save(mine)
        // a reaction whose `e` tag is not an event id (a relay would store it; the parser must not trust it)
        e.save(EventBuilder(Kind(7u), "+").tags(listOf(Tag.parse(listOf("e", "not-an-id")), Tag.publicKey(me.publicKey()))).customCreatedAt(Timestamp.fromSecs(200u)).signWithKeys(alice))
        e.save(EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs(300u)).signWithKeys(bob))
        val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
        assertEquals(listOf(bob.publicKey().toHex()), repo.list().map { it.from })
        val result = repo.notes(setOf("not-an-id", "G".repeat(64), mine.id().toHex()))
        assertEquals(AboutNote.Missing, result["not-an-id"])
        assertEquals(AboutNote.Missing, result["G".repeat(64)])
        assertEquals("my note", (result[mine.id().toHex()] as AboutNote.Found).note.content)
    }

    @Test
    fun `a like of my repost finds the repost, and a failing database read yields Missing instead of an exception`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val original = EventBuilder.textNote("alice wrote this").customCreatedAt(Timestamp.fromSecs(100u)).signWithKeys(alice); e.save(original)
        val myRepost = EventBuilder.repost(original, null).customCreatedAt(Timestamp.fromSecs(110u)).signWithKeys(me); e.save(myRepost)
        val repo = NostrNotificationsRepository(e, NostrTipsRepository(e) { 1000 }) { 1000 }
        val found = repo.notes(setOf(myRepost.id().toHex()))[myRepost.id().toHex()] as AboutNote.Found
        assertEquals(6, found.note.kind)
        assertEquals("alice wrote this", found.note.repostOf?.content)
        e.close()
        // the engine is closed: every read throws; the lookup answers Missing for an unknown id and keeps the memo for the known one
        val after = repo.notes(setOf(myRepost.id().toHex(), "d".repeat(64)))
        assertEquals(AboutNote.Missing, after["d".repeat(64)])
        assertEquals(found, after[myRepost.id().toHex()])
    }
}
