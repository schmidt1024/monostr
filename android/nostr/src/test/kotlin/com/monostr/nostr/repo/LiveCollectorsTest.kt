package com.monostr.nostr.repo

import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.RustConvert
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.tips.PaymentInfo
import com.monostr.tips.TipReceipt
import com.monostr.tips.TipType
import com.monostr.tips.Watcher
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.giftWrap
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spec wave 2 B2 (plan B Task 1, baseline): every live collector on the engine's event stream, end to end, through a
 * loopback relay that pushes an event after the subscription. Task 4 moves the collectors from `events` to `incoming`;
 * these tests stay as they are. Plan C reworks the thread and may wait on its own REQ shape there.
 */
class LiveCollectorsTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val meHex = me.publicKey().toHex()
    private val alice = Keys.generate()
    private val aliceHex = alice.publicKey().toHex()
    private val watcher = Keys.generate()
    private val clock = 1_000_000L

    private suspend fun engine(relay: SilentWsRelay): NostrEngine = connectedEngine(dir, relay, me.secretKey().toHex())

    /** Collects [flow] on IO into a list until the job is cancelled. */
    private fun <T> collecting(flow: Flow<T>): Pair<Job, CopyOnWriteArrayList<T>> {
        val seen = CopyOnWriteArrayList<T>()
        return CoroutineScope(Dispatchers.IO).launch { flow.collect { seen += it } } to seen
    }

    /** [reqs] REQs matching [matching] reached the relay. The relay registers a subscription for [SilentWsRelay.push] only after it recorded the REQ, and the collector starts after its subscribe returns: pushes go through [pushUntil]. */
    private suspend fun subscribed(relay: SilentWsRelay, reqs: Int, matching: (String) -> Boolean) {
        assertTrue(awaitTrue { relay.requests.count(matching) >= reqs }, "requests: ${relay.requests}")
    }

    /** Pushes [eventJson] again on every poll until [done]: one pushed before the relay registered the subscription is dropped (the collectors deduplicate). */
    private suspend fun pushUntil(relay: SilentWsRelay, vararg eventJson: String, done: suspend () -> Boolean): Boolean =
        awaitTrue { eventJson.forEach { relay.push(it) }; done() }

    private fun signed(keys: Keys, unsigned: UnsignedEvent): Event = RustConvert.toBuilder(unsigned).signWithKeys(keys)

    private fun alicePaymentInfo(): Event =
        signed(alice, PaymentInfo.build(MoneroKeys.generate().address(Network.MAINNET), Watcher.Http("https://watcher.example", watcher.publicKey().toHex()), clock))

    @Test
    fun `the feed's live flow shows a member's note that arrives later`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            val (job, lists) = collecting(NostrFeedRepository(e).live(FeedSource.Authors(listOf(aliceHex))))
            try {
                subscribed(relay, 1) { "\"since\"" in it }
                val now = (System.currentTimeMillis() / 1000).toULong()
                val live = EventBuilder.textNote("live").customCreatedAt(Timestamp.fromSecs(now)).signWithKeys(alice).asJson()
                assertTrue(pushUntil(relay, live) { lists.lastOrNull()?.any { it.content == "live" } == true }, "the live note never showed: $lists")
            } finally {
                job.cancel(); e.close()
            }
        }
    }

    @Test
    fun `an open thread shows a reply that arrives later`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            val root = EventBuilder.textNote("root").signWithKeys(alice)
            e.save(root)
            val rootHex = root.id().toHex()
            val (job, views) = collecting(NostrThreadRepository(e).observe(rootHex))
            try {
                // the #e fetch, the kind 5 fetch and the live subscription all name the root
                subscribed(relay, 3) { rootHex in it }
                val reply = EventBuilder.textNote("live reply").tags(listOf(Tag.parse(listOf("e", rootHex, "", "root")))).signWithKeys(alice)
                assertTrue(pushUntil(relay, reply.asJson()) { views.lastOrNull()?.replies?.any { it.content == "live reply" } == true }, "the live reply never showed")
            } finally {
                job.cancel(); e.close()
            }
        }
    }

    @Test
    fun `a note's tip summary counts a receipt that arrives later`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            e.save(alicePaymentInfo())
            val note = EventBuilder.textNote("tip me").signWithKeys(alice)
            e.save(note)
            val noteId = note.id().toHex()
            e.save(signed(watcher, TipReceipt.build(noteId, aliceHex, meHex, 1_000, "1".repeat(64), TipType.LIKE, clock + 1)))
            val (job, summaries) = collecting(NostrTipsRepository(e) { clock }.observeReceipts(mapOf(noteId to aliceHex)))
            try {
                // the receipts fetch and the live subscription
                subscribed(relay, 2) { noteId in it }
                val receipt = signed(watcher, TipReceipt.build(noteId, aliceHex, meHex, 2_000, "2".repeat(64), TipType.LIKE, clock + 2)).asJson()
                assertTrue(pushUntil(relay, receipt) { summaries.lastOrNull()?.get(noteId)?.count == 2 }, "summaries: $summaries")
            } finally {
                job.cancel(); e.close()
            }
        }
    }

    @Test
    fun `a profile's tip set takes a receipt that arrives later`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            e.save(alicePaymentInfo())
            val watcherHex = watcher.publicKey().toHex()
            val (job, sets) = collecting(NostrTipsRepository(e) { clock }.observeProfileReceipts(aliceHex, since = clock))
            try {
                subscribed(relay, 2) { watcherHex in it }
                val receipt = signed(watcher, TipReceipt.build(null, aliceHex, null, 1_000, "3".repeat(64), TipType.TIP, clock + 5)).asJson()
                assertTrue(pushUntil(relay, receipt) { sets.lastOrNull()?.contains("3".repeat(64)) == true }, "sets: $sets")
            } finally {
                job.cancel(); e.close()
            }
        }
    }

    @Test
    fun `incoming wraps deliver a gift wrap for the user and skip one for somebody else`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            val repo = NostrDmRepository(e)
            val bob = Keys.generate()
            val got = async(Dispatchers.IO) { withTimeoutOrNull(10_000) { repo.incomingWraps().first() } }
            val sub = repo.subscribeWraps(listOf(relay.url), since = 0)
            subscribed(relay, 1) { "1059" in it }
            val stranger = Keys.generate().publicKey()
            val other = giftWrap(NostrSigner.keys(bob), stranger, EventBuilder.privateMsgRumor(stranger, "not for me").build(bob.publicKey()), emptyList())
            val mine = giftWrap(NostrSigner.keys(bob), me.publicKey(), EventBuilder.privateMsgRumor(me.publicKey(), "hi").build(bob.publicKey()), emptyList())
            assertTrue(pushUntil(relay, other.asJson(), mine.asJson()) { got.isCompleted }, "no wrap came through")
            assertEquals(mine.id().toHex(), got.await()?.id()?.toHex())
            repo.unsubscribe(sub)
            e.close()
        }
    }

    @Test
    fun `the mute list adopts a newer list another device publishes`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val list = NostrPrivateList(e, LocalSigner(me.secretKey().toHex()), kind = 10000, tag = "p", now = { 2000 }, watch = scope)
                list.ensureLoaded(interactive = false)
                assertTrue(list.state.value.loaded, "state: ${list.state.value}")
                // the load's fetch and the watch subscription
                subscribed(relay, 2) { "10000" in it }
                val a = "a".repeat(64)
                val newer = EventBuilder(Kind(10000u), "").tags(listOf(Tag.parse(listOf("p", a)))).customCreatedAt(Timestamp.fromSecs(3000u)).signWithKeys(me)
                assertTrue(pushUntil(relay, newer.asJson()) { list.state.value.ids == listOf(a) }, "state: ${list.state.value}")
            } finally {
                scope.cancel(); e.close()
            }
        }
    }
}
