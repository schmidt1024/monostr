package com.monostr.nostr.repo

import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.RustConvert
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.NoteMapper
import com.monostr.tips.PaymentInfo
import com.monostr.tips.TipReceipt
import com.monostr.tips.TipRequest
import com.monostr.tips.TipType
import com.monostr.tips.Watcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Spec wave 2 B4, call sites 1–6: a stored-nowhere note comes from the fastest relay while another relay never answers. */
class EarlyExitIdSitesTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val meHex = me.publicKey().toHex()
    private val alice = Keys.generate()
    private val aliceHex = alice.publicKey().toHex()

    /** Before: the lookup waited for the silent relay (8 s, quotes 5 s); now it must be done well within 3 s. */
    private suspend fun <T> within3s(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(3_000) { block() } }

    @Test
    fun `1 a thread whose note is not stored opens from the fastest relay`() = runTest {
        val note = EventBuilder.textNote("focused").signWithKeys(alice)
        SilentWsRelay(serve = listOf(note.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val view = within3s { NostrThreadRepository(e).observe(note.id().toHex()).first() }
                    assertEquals("focused", view.focused.content)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `2 the walk above a reply gets its parent from the fastest relay`() = runTest {
        val parent = EventBuilder.textNote("parent").signWithKeys(alice)
        val parentHex = parent.id().toHex()
        val reply = EventBuilder.textNote("reply").tags(listOf(Tag.parse(listOf("e", parentHex, "", "root")))).signWithKeys(alice)
        SilentWsRelay(serve = listOf(parent.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val slots = within3s { NostrConversationRepository(e).ancestors(NoteMapper.note(reply)!!) }
                    assertEquals(listOf(parentHex), slots.map { it.id })
                    assertTrue(slots.single() is Slot.Shown)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `3 a quote card fills from the fastest session relay`() = runTest {
        val quoted = EventBuilder.textNote("quoted").signWithKeys(alice)
        SilentWsRelay(serve = listOf(quoted.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val result = within3s { NostrQuoteRepository(e).get(quoted.id().toHex(), emptyList()) }
                    assertEquals("quoted", (result as QuoteResult.Found).note.content)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `4 the tippers sheet gets an intent from the fastest relay`() = runTest {
        val watcher = Keys.generate()
        val clock = System.currentTimeMillis() / 1000 // real time: rust-nostr drops an intent whose expiration tag is in the past
        val info = RustConvert.toBuilder(PaymentInfo.build(MoneroKeys.generate().address(Network.MAINNET), Watcher.Http("https://watcher.example", watcher.publicKey().toHex()), clock)).signWithKeys(alice)
        val note = EventBuilder.textNote("tip me").signWithKeys(alice)
        val noteId = note.id().toHex()
        val enabled = PaymentInfo.parse(RustConvert.toTips(info)) as PaymentInfo.Enabled
        val intent = RustConvert.toBuilder(TipRequest.prepare(enabled, aliceHex, noteId, 1_000_000_000, TipType.LIKE, "hi", clock).unsignedEvent).signWithKeys(me)
        val receipt = RustConvert.toBuilder(TipReceipt.build(noteId, aliceHex, meHex, 1_000_000_000, intent.id().toHex(), TipType.LIKE, clock + 1)).signWithKeys(watcher)
        SilentWsRelay(serve = listOf(intent.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    e.save(info); e.save(note); e.save(receipt)
                    val tippers = within3s { NostrTipsRepository(e) { clock }.tippers(noteId, aliceHex) }
                    assertEquals(listOf("hi"), tippers.map { it.comment })
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `5 a notification's parent note comes from the fastest relay`() = runTest {
        val parent = EventBuilder.textNote("parent").signWithKeys(alice)
        val id = parent.id().toHex()
        SilentWsRelay(serve = listOf(parent.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val notes = within3s { NostrNotificationsRepository(e, NostrTipsRepository(e)).notes(setOf(id)) }
                    assertTrue(notes[id] is AboutNote.Found, "got ${notes[id]}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `6 a bookmarked note that is not stored comes from the fastest relay`() = runTest {
        val saved = EventBuilder.textNote("saved").signWithKeys(alice)
        val id = saved.id().toHex()
        SilentWsRelay(serve = listOf(saved.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    e.save(EventBuilder(Kind(10003u), "").tags(listOf(Tag.parse(listOf("e", id)))).customCreatedAt(Timestamp.fromSecs(1u)).signWithKeys(me))
                    val repo = NostrBookmarksRepository(e, LocalSigner(me.secretKey().toHex()), now = { 2000 })
                    repo.ensureLoaded()
                    val entries = within3s { repo.entries() }
                    assertEquals("saved", entries.single().note?.content)
                } finally {
                    e.close()
                }
            }
        }
    }
}
