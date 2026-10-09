package com.monostr.nostr.repo

import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.RustConvert
import com.monostr.tips.Kinds
import com.monostr.tips.PaymentId
import com.monostr.tips.PaymentInfo
import com.monostr.tips.TipReceipt
import com.monostr.tips.TipRequest
import com.monostr.tips.TipType
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.nio.file.Path

class TipsRepositoryTest {
    @TempDir lateinit var dir: Path

    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val watcher = Keys.generate()
    private val otherWatcher = Keys.generate()
    private val aliceWallet = MoneroKeys.generate()
    private val aliceAddress = aliceWallet.address(Network.MAINNET)
    private var clock = 1_000_000L

    private suspend fun engine(): NostrEngine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())

    private fun signed(keys: Keys, unsigned: UnsignedEvent): Event = RustConvert.toBuilder(unsigned).signWithKeys(keys)

    private fun alicePaymentInfo(): Event = signed(alice, PaymentInfo.build(aliceAddress, "https://watcher.example", watcher.publicKey().toHex(), clock))

    private fun receipt(by: Keys, noteId: String, recipient: String, amount: Long, intentId: String, at: Long): Event =
        signed(by, TipReceipt.build(noteId, recipient, me.publicKey().toHex(), amount, intentId, TipType.LIKE, at))

    @Test
    fun `payment info is read from the database and malformed or missing info is disabled`() = runTest {
        val e = engine()
        e.save(alicePaymentInfo())
        val repo = NostrTipsRepository(e) { clock }
        val info = repo.paymentInfo(alice.publicKey().toHex())
        assertTrue(info is PaymentInfo.Enabled, "got $info")
        assertEquals(watcher.publicKey().toHex(), (info as PaymentInfo.Enabled).watcherPubkey)
        assertEquals(aliceAddress, info.address)
        assertEquals(PaymentInfo.Disabled, repo.paymentInfo(Keys.generate().publicKey().toHex()))
        // an address-less kind 10037 means "no Monero"
        val bob = Keys.generate()
        e.save(signed(bob, PaymentInfo.buildDisabled(clock)))
        assertEquals(PaymentInfo.Disabled, repo.paymentInfo(bob.publicKey().toHex()))
    }

    @Test
    fun `missing payment info is cached for maxAge like a present one`() = runTest {
        val repo = NostrTipsRepository(engine()) { clock }
        val unknown = Keys.generate().publicKey().toHex()
        assertEquals(null, repo.lastFetchAt(unknown))
        assertEquals(PaymentInfo.Disabled, repo.paymentInfo(unknown))
        val first = clock
        assertEquals(first, repo.lastFetchAt(unknown))
        clock += 3000
        assertEquals(PaymentInfo.Disabled, repo.paymentInfo(unknown))
        assertEquals(first, repo.lastFetchAt(unknown), "within maxAge no relay fetch")
        clock += 601
        assertEquals(PaymentInfo.Disabled, repo.paymentInfo(unknown))
        assertEquals(clock, repo.lastFetchAt(unknown), "after maxAge the relays are asked again")
    }

    @Test
    fun `own payment info is published, replaced and disabled once a relay accepted it`() = runTest {
        SilentWsRelay(acceptEvents = true, answerEose = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
            try {
                e.connect()
                val repo = NostrTipsRepository(e) { clock }
                val myWallet = MoneroKeys.generate().address(Network.MAINNET)
                val published = repo.publishPaymentInfo(myWallet, "https://watcher.example/", watcher.publicKey().toHex())
                assertEquals(Kinds.PAYMENT_INFO, published.kind)
                assertTrue(published.sentToAny, "failed: ${published.failedRelays}")
                val mine = repo.paymentInfo(me.publicKey().toHex()) as PaymentInfo.Enabled
                assertEquals(myWallet, mine.address)
                assertEquals("https://watcher.example", mine.watcherUrl)
                clock += 10
                assertTrue(repo.disablePaymentInfo().sentToAny)
                assertEquals(PaymentInfo.Disabled, repo.paymentInfo(me.publicKey().toHex()))
                assertEquals(1, e.query(Filter().kind(Kind(10037u))).size, "replaceable: only the newest kind 10037 stays")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `payment info no relay accepted is not kept, so nobody is tipped at an address that was never announced`() = runTest {
        val e = engine() // no relay at all
        val repo = NostrTipsRepository(e) { clock }
        val myWallet = MoneroKeys.generate().address(Network.MAINNET)
        val published = repo.publishPaymentInfo(myWallet, "https://watcher.example/", watcher.publicKey().toHex())
        assertFalse(published.sentToAny)
        assertEquals(PaymentInfo.Disabled, repo.cachedPaymentInfo(me.publicKey().toHex()))
        assertTrue(e.query(Filter().kind(Kind(10037u))).isEmpty(), "the tip sheet reads the local copy when the relays are slow")
    }

    @Test
    fun `sendIntent signs and stores the intent and tries the watcher relays`() = runTest {
        val e = engine()
        val repo = NostrTipsRepository(e) { clock }
        val info = PaymentInfo.parse(RustConvert.toTips(alicePaymentInfo())) as PaymentInfo.Enabled
        val noteId = "1".repeat(64)
        val prepared = TipRequest.prepare(info, alice.publicKey().toHex(), noteId, 5_000_000_000, TipType.BOOST, "danke", clock, PaymentId.parse("a1b2c3d4e5f60718")!!)
        val result = repo.sendIntent(prepared, listOf("not a relay url", "wss://watcher-relay.invalid"))
        assertEquals(Kinds.TIP_INTENT, result.kind)
        val stored = e.eventById(result.eventId)!!
        assertEquals(me.publicKey().toHex(), stored.author().toHex())
        assertTrue(stored.verify())
        val tags = stored.tags().toVec().map { it.asVec() }
        assertEquals(noteId, tags.first { it[0] == "e" }[1])
        assertEquals(alice.publicKey().toHex(), tags.first { it[0] == "p" }[1])
        assertEquals("5000000000", tags.first { it[0] == "amount" }[1])
        assertEquals("a1b2c3d4e5f60718", tags.first { it[0] == "pid" }[1])
        assertEquals("boost", tags.first { it[0] == "type" }[1])
        assertEquals((clock + 86_400).toString(), tags.first { it[0] == "expiration" }[1])
        assertEquals("danke", stored.content())
        assertFalse(result.sentToAny)
        // the temporary relay is gone again
        assertTrue(e.relayUrls().isEmpty(), "relays: ${e.relayUrls()}")
    }

    @Test
    fun `receipts are counted only when the recipient's watcher signed them`() = runTest {
        val e = engine()
        e.save(alicePaymentInfo())
        val note = EventBuilder.textNote("tip me").signWithKeys(alice); e.save(note)
        val noteId = note.id().toHex()
        val aliceHex = alice.publicKey().toHex()
        val intent = e.signAndSend(TipRequest.prepare(PaymentInfo.parse(RustConvert.toTips(alicePaymentInfo())) as PaymentInfo.Enabled, aliceHex, noteId, 1_000_000_000, TipType.LIKE, "hi", clock).unsignedEvent)
        e.save(receipt(watcher, noteId, aliceHex, 1_000_000_000, intent.eventId, clock + 1))
        e.save(receipt(watcher, noteId, aliceHex, 2_000_000_000, intent.eventId, clock + 2))       // second payment, same intent: summed
        e.save(receipt(otherWatcher, noteId, aliceHex, 9_000_000_000, intent.eventId, clock + 3))  // foreign watcher: ignored
        e.save(receipt(watcher, noteId, me.publicKey().toHex(), 9_000_000_000, intent.eventId, clock + 4)) // p mismatch: ignored
        val bob = Keys.generate()
        val bobNote = EventBuilder.textNote("no monero").signWithKeys(bob); e.save(bobNote)
        e.save(receipt(watcher, bobNote.id().toHex(), bob.publicKey().toHex(), 1, intent.eventId, clock + 5)) // recipient without payment info: ignored
        val repo = NostrTipsRepository(e) { clock }
        val summaries = repo.observeReceipts(mapOf(noteId to aliceHex, bobNote.id().toHex() to bob.publicKey().toHex())).first()
        val s = summaries.getValue(noteId)
        assertEquals(3_000_000_000, s.total)
        assertEquals(2, s.count)
        assertEquals(listOf(clock + 2, clock + 1), s.receipts.map { it.createdAt })
        assertFalse(summaries.containsKey(bobNote.id().toHex()))
        val tippers = repo.tippers(noteId, aliceHex)
        assertEquals(2, tippers.size)
        assertEquals("hi", tippers[0].comment)
        assertEquals(me.publicKey().toHex(), tippers[0].receipt.senderPubkey)
        assertTrue(repo.observeReceipts(emptyMap()).first().isEmpty())
    }

    @Test
    fun `tipper intents are fetched in chunks of fifty ids`() {
        val ids = (0 until 120).map { it.toString(16).padStart(64, '0') }
        val filters = NostrTipsRepository.intentFilters(ids)
        assertEquals(3, filters.size)
        val sizes = filters.map { f -> Regex("\"ids\":\\[(.*?)\\]").find(f.asJson())!!.groupValues[1].split(",").size }
        assertEquals(listOf(50, 50, 20), sizes)
        assertTrue(NostrTipsRepository.intentFilters(emptyList()).isEmpty())
    }

    @Test
    fun `an anonymous intent is signed by a one-time key, goes to the watcher relays only and is not stored`() = runTest {
        SilentWsRelay(acceptEvents = true).use { watcherRelay ->
            SilentWsRelay(acceptEvents = true).use { ownRelay ->
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(ownRelay.url))
                try {
                    e.connect()
                    val repo = NostrTipsRepository(e) { clock }
                    val info = PaymentInfo.parse(RustConvert.toTips(alicePaymentInfo())) as PaymentInfo.Enabled
                    val aliceHex = alice.publicKey().toHex()
                    fun prepared() = TipRequest.prepare(info, aliceHex, null, 1_000_000_000, TipType.TIP, "", clock, anonymous = true)

                    val first = repo.sendAnonymousIntent(prepared(), listOf(watcherRelay.url))
                    assertTrue(first.result.sentToAny, "failed: ${first.result.failedRelays}")
                    assertTrue(awaitTrue { watcherRelay.events.size == 1 })
                    val sent = Event.fromJson(first.eventJson)
                    assertEquals(first.result.eventId, sent.id().toHex())
                    assertTrue(sent.verify())
                    assertNotEquals(me.publicKey().toHex(), sent.author().toHex())
                    val tags = sent.tags().toVec().map { it.asVec() }
                    assertTrue(tags.none { it[0] == "e" })
                    assertTrue(tags.any { it == listOf("anon") })
                    assertEquals(aliceHex, tags.first { it[0] == "p" }[1])
                    assertEquals("", sent.content())

                    assertNull(e.eventById(first.result.eventId), "never stored in the engine database")
                    assertTrue(ownRelay.events.isEmpty(), "never sent over the user's own connection")
                    assertEquals(1, e.relayUrls().size, "the watcher relay never joins the user's pool")

                    val second = repo.sendAnonymousIntent(prepared(), listOf(watcherRelay.url))
                    assertNotEquals(sent.author().toHex(), Event.fromJson(second.eventJson).author().toHex(), "a fresh key per tip")

                    val again = repo.resendAnonymousIntent(first.eventJson, listOf(watcherRelay.url))
                    assertEquals(first.result.eventId, again.eventId)
                    assertTrue(awaitTrue { watcherRelay.events.count { it.contains(first.result.eventId) } == 2 })
                    assertTrue(ownRelay.events.isEmpty())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `an anonymous intent needs watcher relays and an intent marked anonymous`() = runTest {
        val repo = NostrTipsRepository(engine()) { clock }
        val info = PaymentInfo.parse(RustConvert.toTips(alicePaymentInfo())) as PaymentInfo.Enabled
        val aliceHex = alice.publicKey().toHex()
        val anonymous = TipRequest.prepare(info, aliceHex, null, 1, TipType.TIP, "", clock, anonymous = true)
        val public = TipRequest.prepare(info, aliceHex, null, 1, TipType.TIP, "", clock)
        assertTrue(runCatching { repo.sendAnonymousIntent(anonymous, emptyList()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repo.sendAnonymousIntent(public, listOf("wss://w.example")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `profile receipts count without a note, with or without a sender, from the recipient's watcher and since the mark`() = runTest {
        val e = engine()
        e.save(alicePaymentInfo())
        val aliceHex = alice.publicKey().toHex()
        val meHex = me.publicKey().toHex()
        fun profileReceipt(by: Keys, sender: String?, intentId: String, at: Long): Event =
            signed(by, TipReceipt.build(null, aliceHex, sender, 1_000, intentId, TipType.TIP, at))
        e.save(profileReceipt(watcher, meHex, "1".repeat(64), clock + 10))            // public profile tip
        e.save(profileReceipt(watcher, null, "2".repeat(64), clock + 11))             // anonymous profile tip
        e.save(profileReceipt(otherWatcher, null, "3".repeat(64), clock + 12))        // foreign watcher: ignored
        e.save(receipt(watcher, "9".repeat(64), aliceHex, 1_000, "4".repeat(64), clock + 13)) // note tip: not a profile tip
        e.save(profileReceipt(watcher, null, "5".repeat(64), clock - 500))            // before the mark
        val repo = NostrTipsRepository(e) { clock }
        assertEquals(setOf("1".repeat(64), "2".repeat(64)), repo.observeProfileReceipts(aliceHex, since = clock).first())
        assertTrue(repo.observeProfileReceipts(Keys.generate().publicKey().toHex(), since = 0).first().isEmpty(), "no payment info: nothing")
    }

    @Test
    fun `the receipt of an anonymous profile tip is watched over a detached connection, never over the engine's`() = runTest {
        val aliceHex = alice.publicKey().toHex()
        val valid = signed(watcher, TipReceipt.build(null, aliceHex, null, 1_000, "1".repeat(64), TipType.TIP, clock + 10))
        val foreign = signed(otherWatcher, TipReceipt.build(null, aliceHex, null, 1_000, "2".repeat(64), TipType.TIP, clock + 11))
        val noteReceipt = receipt(watcher, "9".repeat(64), aliceHex, 1_000, "3".repeat(64), clock + 12)
        SilentWsRelay(serve = listOf(valid.asJson(), foreign.asJson(), noteReceipt.asJson())).use { watcherRelay ->
            SilentWsRelay(answerEose = true).use { ownRelay ->
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(ownRelay.url))
                try {
                    e.connect()
                    e.save(alicePaymentInfo())
                    val repo = NostrTipsRepository(e) { clock }
                    val ids = repo.observeAnonymousProfileReceipts(aliceHex, since = clock, listOf(watcherRelay.url)).first()
                    assertEquals(setOf("1".repeat(64)), ids)
                    assertTrue(watcherRelay.requests.isNotEmpty())
                    assertTrue(ownRelay.requests.none { it.contains("9739") }, "own relay saw: ${ownRelay.requests}")
                    assertNull(e.eventById(valid.id().toHex()), "a receipt seen there is not stored")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `without cached payment info nothing is asked`() = runTest {
        SilentWsRelay(answerEose = true).use { watcherRelay ->
            SilentWsRelay(answerEose = true).use { ownRelay ->
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(ownRelay.url))
                try {
                    e.connect()
                    val repo = NostrTipsRepository(e) { clock }
                    val before = ownRelay.requests.size
                    val ids = repo.observeAnonymousProfileReceipts(Keys.generate().publicKey().toHex(), since = clock, listOf(watcherRelay.url)).toList()
                    assertTrue(ids.isEmpty(), "the flow ends without a value")
                    assertTrue(watcherRelay.requests.isEmpty())
                    assertEquals(before, ownRelay.requests.size, "no payment-info fetch over the user's connection: ${ownRelay.requests}")
                    assertTrue(ownRelay.requests.none { it.contains("10037") })
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a public send refuses an intent marked anonymous`() = runTest {
        val e = engine()
        val repo = NostrTipsRepository(e) { clock }
        val info = PaymentInfo.parse(RustConvert.toTips(alicePaymentInfo())) as PaymentInfo.Enabled
        val anonymous = TipRequest.prepare(info, alice.publicKey().toHex(), null, 1, TipType.TIP, "", clock, anonymous = true)
        assertTrue(runCatching { repo.sendIntent(anonymous, emptyList()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(e.query(Filter().kind(Kind(Kinds.TIP_INTENT.toUShort()))).isEmpty(), "nothing signed under the user's key")
    }
}
