package com.monostr.app.dm

import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.DropReason
import com.monostr.app.data.dm.InMemoryDmStore
import com.monostr.app.data.dm.PendingSince
import com.monostr.app.ui.FakeDmRelays
import com.monostr.app.ui.FakeDmRepository
import com.monostr.app.ui.FakeDmSettings
import com.monostr.app.ui.FakePublish
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.repo.DmIncoming
import com.monostr.nostr.repo.DmRelaysRepository
import com.monostr.nostr.repo.DmSendResult
import com.monostr.nostr.repo.OwnDmRelays
import com.monostr.nostr.repo.Unwrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

class DmSyncTest {
    private val me = Keys.generate().publicKey().toHex()
    private val bob = Keys.generate().publicKey().toHex()
    private val relay = "wss://relay.monostr.com"
    private val day = 86_400L

    private fun wrapEvent(): Event = EventBuilder(Kind(1059u), "x").tags(listOf(Tag.parse(listOf("p", me)))).signWithKeys(Keys.generate())

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private inner class Rig(scope: CoroutineScope, own: OwnDmRelays, peers: Map<String, List<String>> = emptyMap()) {
        var clock = 10_000_000L
        val repo = FakeDmRepository()
        val relays = FakeDmRelays(own, peers)
        val store = InMemoryDmStore()
        val settings = FakeDmSettings(listOf(relay))
        val publish = FakePublish()
        val attached = ArrayList<List<String>>()
        val detached = ArrayList<List<String>>()
        /** What `attach` reports as taken; by default everything it was given. */
        var holds: (List<String>) -> List<String> = { it }
        val closed = MutableSharedFlow<NostrEngine.ClosedSubscription>(extraBufferCapacity = 16)
        val authOk = MutableSharedFlow<String>(extraBufferCapacity = 16)
        /** What `authOkSeq` reports per relay (the engine's sequence of its latest AUTH OK). */
        val authOkSeqs = HashMap<String, Long>()
        val authRejected = MutableSharedFlow<NostrEngine.AuthRejection>(extraBufferCapacity = 16)
        /** What `authRejection` reports per relay (the engine's latest refused AUTH). */
        val authRejections = HashMap<String, NostrEngine.AuthRejection>()
        /** The engine database as seen by `loadWrap`. */
        val db = HashMap<String, Event>()
        val dms = DmSync(
            repo, relays, store, settings, publish,
            attach = { attached += it; holds(it) }, detach = { detached += it },
            closed = closed, scope = scope, loadWrap = { db[it] }, now = { clock }, authOk = authOk, authOkSeq = { authOkSeqs[it] },
            authRejected = authRejected, authRejection = { authRejections[it] },
        )
    }

    @Test
    fun `start fetches thirty days on first run, subscribes and neither looks up nor publishes the own list`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.None)
        r.dms.start()
        assertEquals(0, r.relays.ownCalls)
        assertTrue(r.publish.calls.isEmpty())
        assertEquals(listOf(listOf(relay)), r.attached)
        assertEquals(listOf(listOf(relay) to r.clock - 30 * day), r.repo.fetchCalls)
        assertEquals(r.clock, r.store.lastSync())
        assertEquals(listOf(listOf(relay) to r.clock - 2 * day), r.repo.subscribeCalls)
        assertFalse(r.dms.state.value.syncing)

        // idempotent: a second start neither attaches, fetches nor subscribes again
        r.dms.start()
        assertEquals(1, r.attached.size); assertEquals(1, r.repo.fetchCalls.size); assertEquals(1, r.repo.subscribeCalls.size)

        // later rounds fetch from the last sync minus the two-day gift-wrap window
        val first = r.clock
        r.clock += 3_600
        assertTrue(r.dms.sync(interactive = false).isEmpty())
        assertEquals(listOf(relay) to first - 2 * day, r.repo.fetchCalls.last())
        assertEquals(r.clock, r.store.lastSync())

        // a failed fetch leaves lastSync untouched
        r.repo.fetchError = IllegalStateException("offline")
        r.clock += 3_600
        assertTrue(runCatching { r.dms.sync(interactive = false) }.isFailure)
        assertEquals(r.clock - 3_600, r.store.lastSync())
        assertFalse(r.dms.state.value.syncing)
        assertEquals(0, r.relays.ownCalls)
    }

    @Test
    fun `adoptOwnList publishes the stored list when none exists, once per account`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.None)
        r.dms.start()
        r.dms.adoptOwnList()
        assertEquals(listOf("dmrelays:1"), r.publish.calls)
        assertTrue(r.settings.listAdoptedState.value)
        assertEquals(listOf(relay), r.settings.relayState.value)
        // the list did not change: no refetch, the run keeps going
        assertEquals(1, r.repo.fetchCalls.size); assertEquals(1, r.repo.subscribeCalls.size)

        r.dms.adoptOwnList()
        assertEquals(1, r.relays.ownCalls)

        // a new process (a new DmSync over the same settings) does not look it up again
        val next = DmSync(
            r.repo, r.relays, r.store, r.settings, r.publish,
            attach = { it }, detach = {}, closed = r.closed, scope = eager(), loadWrap = { null }, now = { r.clock },
        )
        next.adoptOwnList()
        assertEquals(1, r.relays.ownCalls)
        assertEquals(listOf("dmrelays:1"), r.publish.calls)
    }

    @Test
    fun `a changed own list resets lastSync and refetches thirty days from the new relays`() = runTest {
        val inbox = listOf("wss://inbox.example.com")
        val r = Rig(eager(), own = OwnDmRelays.Found(inbox))
        r.dms.start()
        r.clock += 5 * day
        r.dms.sync(interactive = false)
        assertEquals(r.clock, r.store.lastSync())
        var resets = 0
        val watched = object : com.monostr.app.data.dm.DmStore by r.store {
            override suspend fun setLastSync(at: Long) { if (at == 0L) resets++; r.store.setLastSync(at) }
        }
        val dms = DmSync(
            r.repo, r.relays, watched, r.settings, r.publish,
            attach = { r.attached += it; it }, detach = { r.detached += it }, closed = r.closed, scope = eager(), loadWrap = { null }, now = { r.clock },
        )
        dms.start()
        val fetchesBefore = r.repo.fetchCalls.size

        dms.adoptOwnList()
        assertTrue(r.publish.calls.isEmpty())
        assertEquals(inbox, r.settings.relayState.value)
        assertEquals(1, resets)
        // the restart attached the adopted list and fetched the whole window from it
        assertEquals(inbox, r.attached.last())
        assertEquals(inbox to r.clock - 30 * day, r.repo.fetchCalls[fetchesBefore])
        assertEquals(inbox, r.repo.subscribeCalls.last().first)
        assertEquals(listOf(relay), r.detached.last())
    }

    @Test
    fun `stop releases relays and subscription and a later start takes them again`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        r.dms.stop()
        assertEquals(listOf("sub1"), r.repo.unsubscribed)
        assertEquals(listOf(listOf(relay)), r.detached)
        // collectors stopped: a wrap arriving after stop is not handled
        var unwraps = 0
        r.repo.unwrapAnswers = { _, _ -> unwraps++; null }
        r.repo.wraps.emit(wrapEvent())
        assertEquals(0, unwraps)
        assertEquals(0, r.store.pendingCount().first())

        r.dms.start()
        assertEquals(listOf(listOf(relay), listOf(relay)), r.attached)
        assertEquals(2, r.repo.subscribeCalls.size)
    }

    @Test
    fun `stop releases only the relays the run took a reference on`() = runTest {
        val normal = "wss://normal.example.com"
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.settings.relayState.value = listOf(relay, normal)
        r.holds = { urls -> urls - normal } // already in the normal set: attach takes no reference
        r.dms.start()
        assertEquals(listOf(relay, normal), r.repo.subscribeCalls.single().first) // still subscribed on both
        r.dms.stop()
        assertEquals(listOf(listOf(relay)), r.detached)
    }

    @Test
    fun `restart releases the old subscription and attaches the current list`() = runTest {
        val newRelay = "wss://new.example.com"
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        assertEquals(listOf(listOf(relay)), r.attached)
        assertEquals(listOf(relay), r.repo.subscribeCalls.single().first)

        // the inbox list changed (e.g. edited in Settings) between start and restart
        r.settings.relayState.value = listOf(newRelay)
        r.dms.ownRelaysChanged()

        assertEquals(listOf("sub1"), r.repo.unsubscribed)
        assertEquals(listOf(listOf(relay)), r.detached)
        assertEquals(listOf(listOf(relay), listOf(newRelay)), r.attached)
        assertEquals(2, r.repo.subscribeCalls.size)
        assertEquals(listOf(newRelay), r.repo.subscribeCalls.last().first)
        // an edited list refetches the whole window from the new relays
        assertEquals(listOf(newRelay) to r.clock - 30 * day, r.repo.fetchCalls.last())
        assertEquals(0, r.relays.ownCalls)
    }

    @Test
    fun `an incoming wrap of an own message does not duplicate it`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val rumor = "e".repeat(64)
        r.repo.sendResult = DmSendResult(rumor, 900, sentToPeer = true, sentToSelf = true)
        assertEquals(DmStatus.SENT, r.dms.send(bob, "hi"))

        val wrap = wrapEvent()
        val wrapId = wrap.id().toHex()
        r.repo.unwrapAnswers = { w, _ -> DmIncoming(rumor, bob, outgoing = true, content = "hi", createdAt = 900, wrapId = w.id().toHex()) }
        r.dms.start()
        r.repo.wraps.emit(wrap)

        val messages = r.store.messages(bob).first()
        assertEquals(1, messages.size)
        assertEquals(rumor, messages[0].rumorId)
        assertEquals(DmStatus.SENT, messages[0].status)
        assertTrue(messages[0].outgoing)
        assertEquals(wrapId, messages[0].wrapId)
        assertTrue(r.store.hasWrap(wrapId))
        assertEquals(listOf(Triple(bob, "hi", listOf(DmRelaysRepository.FALLBACK))), r.repo.sent)
        assertEquals(listOf(listOf(relay)), r.repo.sentOwnRelays)

        // the same wrap fetched again is skipped and an own message is never reported as new
        r.repo.fetched = listOf(wrap)
        assertTrue(r.dms.sync(interactive = true).isEmpty())
        assertEquals(1, r.store.messages(bob).first().size)
        assertEquals(0, r.store.pendingCount().first())
    }

    @Test
    fun `silent failure parks the wrap and interactive unwrap drains it`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val wrap = wrapEvent()
        val declined = wrapEvent()
        val wrapId = wrap.id().toHex()
        r.db[wrapId] = wrap; r.db[declined.id().toHex()] = declined
        val rumor = "c".repeat(64)
        r.repo.unwrapAnswers = { w, silent ->
            if (silent || w.id().toHex() != wrapId) null
            else DmIncoming(rumor, bob, outgoing = false, content = "hello", createdAt = 500, wrapId = wrapId)
        }
        r.dms.start()
        r.repo.wraps.emit(wrap)
        r.repo.wraps.emit(declined)
        assertEquals(2, r.store.pendingCount().first())
        assertEquals(setOf(wrapId, declined.id().toHex()), r.store.pendingWraps().toSet())
        assertTrue(r.store.messages(bob).first().isEmpty())

        r.clock += 60
        assertEquals(1, r.dms.drainPending())
        assertEquals(0, r.store.pendingCount().first())
        val m = r.store.messages(bob).first().single()
        assertEquals(DmMessage(rumor, bob, false, "hello", 500, r.clock, DmStatus.RECEIVED, wrapId), m)
        assertEquals(0, r.dms.drainPending())

        // a silent sync reports nothing new while the wrap is parked; an interactive one returns it
        val later = wrapEvent()
        val laterRumor = "d".repeat(64)
        r.repo.fetched = listOf(later)
        r.repo.unwrapAnswers = { w, silent -> if (silent) null else DmIncoming(laterRumor, bob, false, "again", 600, w.id().toHex()) }
        assertTrue(r.dms.sync(interactive = false).isEmpty())
        assertEquals(1, r.store.pendingCount().first())
        val fresh = r.dms.sync(interactive = true)
        assertEquals(listOf(laterRumor), fresh.map { it.rumorId })
        assertEquals(DmStatus.RECEIVED, fresh.single().status)
        assertEquals(0, r.store.pendingCount().first())
    }

    @Test
    fun `lastSync advances only after a completed fetch`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        val first = r.clock
        assertEquals(first, r.store.lastSync()) // a completed empty fetch advances it

        // timed out or refused (both reported as not completed): the window stays where it was
        r.repo.fetchCompleted = false
        val wrap = wrapEvent()
        r.repo.fetched = listOf(wrap)
        val rumor = "a".repeat(64)
        r.repo.unwrapAnswers = { w, _ -> DmIncoming(rumor, bob, false, "partial", 500, w.id().toHex()) }
        r.clock += 3_600
        val fresh = r.dms.sync(interactive = false)
        assertEquals(listOf(rumor), fresh.map { it.rumorId }) // what did arrive is still stored
        assertEquals(first, r.store.lastSync())
        r.clock += 3_600
        r.dms.sync(interactive = true)
        assertEquals(first, r.store.lastSync())
        assertEquals(first - 2 * day, r.repo.fetchCalls.last().second) // the next round refetches the same window

        r.repo.fetchCompleted = true
        r.clock += 3_600
        r.dms.sync(interactive = false)
        assertEquals(r.clock, r.store.lastSync())
    }

    @Test
    fun `a self copy of a message the peer never got keeps it FAILED and retryable`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val rumor = "4".repeat(64)
        r.repo.sendResult = DmSendResult(rumor, 900, sentToPeer = false, sentToSelf = true)
        assertEquals(DmStatus.FAILED, r.dms.send(bob, "hi"))

        // our own inbox relay accepted the self-copy and delivers it back
        val selfCopy = wrapEvent()
        r.repo.unwrapAnswers = { w, _ -> DmIncoming(rumor, bob, outgoing = true, content = "hi", createdAt = 900, wrapId = w.id().toHex()) }
        r.dms.start()
        r.repo.wraps.emit(selfCopy)
        val row = r.store.message(rumor)!!
        assertEquals(DmStatus.FAILED, row.status)
        assertEquals(selfCopy.id().toHex(), row.wrapId)

        // still retryable: a new rumor goes out and the row ends SENT
        val retried = "5".repeat(64)
        r.repo.sendResult = DmSendResult(retried, 950, sentToPeer = true, sentToSelf = true)
        assertEquals(DmStatus.SENT, r.dms.retry(rumor))
        assertEquals(listOf(retried), r.store.messages(bob).first().map { it.rumorId })
        assertEquals(2, r.repo.sent.size)
    }

    @Test
    fun `a self copy from another device is stored as SENT`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val rumor = "6".repeat(64)
        r.repo.unwrapAnswers = { w, _ -> DmIncoming(rumor, bob, outgoing = true, content = "from my laptop", createdAt = 900, wrapId = w.id().toHex()) }
        r.dms.start()
        r.repo.wraps.emit(wrapEvent())
        assertEquals(DmStatus.SENT, r.store.message(rumor)!!.status)
    }

    @Test
    fun `a message dated in the far future is stored at most a day ahead`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val future = "f".repeat(64)
        val near = "9".repeat(64)
        r.repo.unwrapAnswers = { w, _ ->
            if (w.id().toHex() == r.db.keys.first()) DmIncoming(future, bob, false, "from the future", r.clock + 30 * day, w.id().toHex())
            else DmIncoming(near, bob, false, "slightly ahead", r.clock + 3_600, w.id().toHex())
        }
        val far = wrapEvent(); r.db[far.id().toHex()] = far
        r.dms.start()
        r.repo.wraps.emit(far)
        r.repo.wraps.emit(wrapEvent())
        assertEquals(r.clock + day, r.store.message(future)!!.createdAt)
        assertEquals(r.clock + 3_600, r.store.message(near)!!.createdAt) // within the day: kept as sent
        // once read, it no longer counts as unread
        r.store.markRead(bob, r.clock + day)
        assertEquals(0, r.store.unreadTotal().first())
    }

    @Test
    fun `a rejected wrap is dropped, never parked and never decrypted again`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val bad = wrapEvent()
        val badId = bad.id().toHex()
        r.db[badId] = bad
        r.repo.unwrapOutcomes = { _, _ -> Unwrap.Rejected }
        r.repo.fetched = listOf(bad)
        r.dms.start()
        assertEquals(listOf(badId), r.repo.unwrapCalls)
        assertEquals(0, r.store.pendingCount().first())
        assertTrue(r.store.isDropped(badId))

        // the next rounds (silent, interactive, live, drain) skip it before decrypting
        assertTrue(r.dms.sync(interactive = false).isEmpty())
        assertTrue(r.dms.sync(interactive = true).isEmpty())
        r.repo.wraps.emit(bad)
        assertEquals(0, r.dms.drainPending())
        assertEquals(listOf(badId), r.repo.unwrapCalls)
        assertEquals(0, r.store.pendingCount().first())
    }

    @Test
    fun `a locked wrap parked twice keeps its first receipt time`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val locked = wrapEvent()
        r.repo.unwrapOutcomes = { _, _ -> Unwrap.Locked }
        r.repo.fetched = listOf(locked)
        r.dms.start()
        val first = r.clock
        assertEquals(PendingSince(1, first), r.store.pendingSince(0))

        r.clock += 900
        r.dms.sync(interactive = false)
        assertEquals(PendingSince(1, first), r.store.pendingSince(0))
        assertEquals(2, r.repo.unwrapCalls.size) // locked wraps are retried: they may open later
        assertFalse(r.store.isDropped(locked.id().toHex()))
    }

    @Test
    fun `send stores SENDING at once and ends SENT or FAILED`() = runTest {
        val bobInbox = listOf("wss://bob.example.com")
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)), peers = mapOf(bob to bobInbox))
        val first = "1".repeat(64)
        val gate = CompletableDeferred<Unit>()
        r.repo.sendGate = gate
        r.repo.sendResult = DmSendResult(first, 900, sentToPeer = false, sentToSelf = true)
        val result = eager().async { r.dms.send(bob, "hi") }

        val provisional = r.store.messages(bob).first().single()
        assertTrue(provisional.rumorId.startsWith("pending-"))
        assertEquals(DmStatus.SENDING, provisional.status)
        assertTrue(provisional.outgoing)
        assertEquals("hi", provisional.content)

        gate.complete(Unit)
        assertEquals(DmStatus.FAILED, result.await())
        val failed = r.store.messages(bob).first().single()
        assertEquals(first, failed.rumorId)
        assertEquals(DmStatus.FAILED, failed.status)
        assertNull(r.store.message(provisional.rumorId))

        // retry builds a new rumor: the row moves to the new id and ends SENT
        r.repo.sendGate = null
        val second = "2".repeat(64)
        r.repo.sendResult = DmSendResult(second, 950, sentToPeer = true, sentToSelf = true)
        assertEquals(DmStatus.SENT, r.dms.retry(first))
        val sent = r.store.messages(bob).first().single()
        assertEquals(second, sent.rumorId)
        assertEquals(DmStatus.SENT, sent.status)
        assertEquals("hi", sent.content)
        assertNull(r.store.message(first))
        assertEquals(listOf(Triple(bob, "hi", bobInbox), Triple(bob, "hi", bobInbox)), r.repo.sent)
        assertEquals(listOf(listOf(relay), listOf(relay)), r.repo.sentOwnRelays)

        // a send that throws leaves a FAILED provisional row; its retry sends the stored text again
        r.repo.sendError = IllegalStateException("offline")
        assertEquals(DmStatus.FAILED, r.dms.send(bob, "second"))
        val thrown = r.store.messages(bob).first().single { it.content == "second" }
        assertTrue(thrown.rumorId.startsWith("pending-"))
        assertEquals(DmStatus.FAILED, thrown.status)
        r.repo.sendError = null
        val third = "3".repeat(64)
        r.repo.sendResult = DmSendResult(third, 990, sentToPeer = true, sentToSelf = true)
        assertEquals(DmStatus.SENT, r.dms.retry(thrown.rumorId))
        assertEquals(Triple(bob, "second", bobInbox), r.repo.sent.last())
        val all = r.store.messages(bob).first()
        assertEquals(setOf(second, third), all.map { it.rumorId }.toSet())
        assertTrue(all.all { it.status == DmStatus.SENT })

        // a sent message is not sent again
        assertEquals(DmStatus.SENT, r.dms.retry(third))
        assertEquals(4, r.repo.sent.size)
    }

    @Test
    fun `auth-required marks the relay and a later start clears it`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "auth-required: x"))
        assertEquals(setOf(relay), r.dms.state.value.authFailed)
        r.closed.emit(NostrEngine.ClosedSubscription("wss://other.example.com", "s", "error: shutting down"))
        assertEquals(setOf(relay), r.dms.state.value.authFailed)
        r.closed.emit(NostrEngine.ClosedSubscription("wss://paid.example.com", "sub1", "auth-required: members only"))
        assertEquals(setOf(relay, "wss://paid.example.com"), r.dms.state.value.authFailed)
        // a feed subscription refused by another relay is not a DM problem
        r.closed.emit(NostrEngine.ClosedSubscription("wss://feed.example.com", "feed-1", "auth-required: x"))
        assertEquals(setOf(relay, "wss://paid.example.com"), r.dms.state.value.authFailed)
        // a silent sync keeps the live subscription and its hints
        r.dms.sync(interactive = false)
        assertEquals(setOf(relay, "wss://paid.example.com"), r.dms.state.value.authFailed)
        assertEquals(1, r.repo.subscribeCalls.size)

        r.dms.stop()
        r.dms.start()
        assertEquals(emptySet<String>(), r.dms.state.value.authFailed)
        assertEquals(2, r.repo.subscribeCalls.size)
        // the new subscription's CLOSED frame fills it again; the old id no longer counts
        r.closed.emit(NostrEngine.ClosedSubscription("wss://paid.example.com", "sub1", "auth-required: members only"))
        assertEquals(emptySet<String>(), r.dms.state.value.authFailed)
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub2", "auth-required: x"))
        assertEquals(setOf(relay), r.dms.state.value.authFailed)

        // a relay that refuses before subscribeWraps has returned is still matched
        r.dms.stop()
        r.repo.onSubscribe = { id ->
            r.closed.emit(NostrEngine.ClosedSubscription(relay, id, "auth-required: x"))
            r.closed.emit(NostrEngine.ClosedSubscription("wss://feed.example.com", "feed-2", "auth-required: x"))
        }
        r.dms.start()
        assertEquals(setOf(relay), r.dms.state.value.authFailed)
    }

    @Test
    fun `an interactive sync resubscribes so a relay that now accepts drops out of the hint`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        // nothing refused: an interactive sync leaves the subscription alone
        r.dms.sync(interactive = true)
        assertEquals(1, r.repo.subscribeCalls.size)
        assertTrue(r.repo.unsubscribed.isEmpty())

        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "auth-required: x"))
        assertEquals(setOf(relay), r.dms.state.value.authFailed)
        r.dms.sync(interactive = true)
        assertEquals(emptySet<String>(), r.dms.state.value.authFailed)
        assertEquals(listOf("sub1"), r.repo.unsubscribed)
        assertEquals(2, r.repo.subscribeCalls.size)
        assertEquals(listOf(relay), r.repo.subscribeCalls.last().first)
        assertEquals(r.clock - 2 * day, r.repo.subscribeCalls.last().second)

        // a late frame on the old id is ignored; the new id re-marks a relay that still refuses
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "auth-required: x"))
        assertEquals(emptySet<String>(), r.dms.state.value.authFailed)
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub2", "auth-required: x"))
        assertEquals(setOf(relay), r.dms.state.value.authFailed)

        // a refusal while resubscribing (before subscribeWraps returns) is matched to the new id
        r.repo.onSubscribe = { id -> r.closed.emit(NostrEngine.ClosedSubscription(relay, id, "auth-required: x")) }
        r.dms.sync(interactive = true)
        assertEquals(listOf("sub1", "sub2"), r.repo.unsubscribed)
        assertEquals(setOf(relay), r.dms.state.value.authFailed)

        // stop releases the latest subscription
        r.dms.stop()
        assertEquals(listOf("sub1", "sub2", "sub3"), r.repo.unsubscribed)
    }

    @Test
    fun `a publish no relay accepted leaves the own list unsettled`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.None)
        r.publish.relaysOk = false
        r.dms.start()
        r.dms.adoptOwnList()
        assertEquals(listOf("dmrelays:1"), r.publish.calls)
        assertFalse(r.settings.listAdoptedState.value)
        r.dms.sync(interactive = true)
        assertEquals(listOf("dmrelays:1", "dmrelays:1"), r.publish.calls)
        r.publish.relaysOk = true
        r.dms.sync(interactive = true)
        assertEquals(3, r.publish.calls.size)
        assertTrue(r.settings.listAdoptedState.value)
        // accepted: settled, no further publish
        r.dms.sync(interactive = true)
        r.dms.stop(); r.dms.start(); r.dms.adoptOwnList()
        assertEquals(3, r.publish.calls.size)
        assertEquals(3, r.relays.ownCalls)
    }

    @Test
    fun `an unknown own list is not overwritten`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Unknown)
        r.dms.start()
        r.dms.adoptOwnList()
        assertTrue(r.publish.calls.isEmpty())
        assertEquals(listOf(relay), r.settings.relayState.value)
        assertFalse(r.settings.listAdoptedState.value)
        // the stored list is still used for this session
        assertEquals(listOf(listOf(relay)), r.attached)
        assertEquals(1, r.repo.subscribeCalls.size)

        // still unknown when the tab opens again: asked again, nothing published
        r.dms.adoptOwnList()
        assertEquals(2, r.relays.ownCalls)
        assertTrue(r.publish.calls.isEmpty())

        // a silent sync does not ask; an interactive one does and settles it
        r.relays.own = OwnDmRelays.None
        r.dms.sync(interactive = false)
        assertEquals(2, r.relays.ownCalls)
        r.dms.sync(interactive = true)
        assertEquals(3, r.relays.ownCalls)
        assertEquals(listOf("dmrelays:1"), r.publish.calls)

        // settled: neither a restart nor another interactive sync asks or publishes again
        r.dms.stop(); r.dms.start(); r.dms.adoptOwnList()
        r.dms.sync(interactive = true)
        assertEquals(3, r.relays.ownCalls)
        assertEquals(listOf("dmrelays:1"), r.publish.calls)
    }

    @Test
    fun `retry supersedes the old rumor id so a late self copy is ignored`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val old = "1".repeat(64)
        val new = "2".repeat(64)
        r.repo.sendResult = DmSendResult(old, 900, sentToPeer = false, sentToSelf = true)
        assertEquals(DmStatus.FAILED, r.dms.send(bob, "hi"))
        r.repo.sendResult = DmSendResult(new, 950, sentToPeer = true, sentToSelf = true)
        assertEquals(DmStatus.SENT, r.dms.retry(old))

        // the first attempt's self copy arrives late: parked silently first, then drained
        val late = wrapEvent()
        val lateId = late.id().toHex()
        r.db[lateId] = late
        r.repo.unwrapAnswers = { w, silent -> if (silent) null else DmIncoming(old, bob, outgoing = true, content = "hi", createdAt = 900, wrapId = w.id().toHex()) }
        r.dms.start()
        r.repo.wraps.emit(late)
        assertEquals(1, r.store.pendingCount().first())
        assertEquals(0, r.dms.drainPending())
        assertEquals(0, r.store.pendingCount().first())

        // and fetched again, unwrapped at once: still skipped
        r.repo.unwrapAnswers = { w, _ -> DmIncoming(old, bob, outgoing = true, content = "hi", createdAt = 900, wrapId = w.id().toHex()) }
        r.repo.fetched = listOf(late)
        assertTrue(r.dms.sync(interactive = true).isEmpty())

        val messages = r.store.messages(bob).first()
        assertEquals(listOf(new), messages.map { it.rumorId })
        assertEquals(DmStatus.SENT, messages.single().status)
        assertNull(r.store.message(old))
        assertEquals(0, r.store.pendingCount().first())
    }

    @Test
    fun `a cancelled caller does not cancel the delivery`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val gate = CompletableDeferred<Unit>()
        r.repo.sendGate = gate
        r.repo.sendResult = DmSendResult("7".repeat(64), 500, sentToPeer = true, sentToSelf = true)
        val caller = eager().launch { r.dms.send(bob, "bye") } // the chat's ViewModel scope
        assertEquals(DmStatus.SENDING, r.store.messages(bob).first().single().status)

        caller.cancel() // the user leaves the chat while the relay has not answered
        assertTrue(caller.isCancelled)
        gate.complete(Unit)

        val row = r.store.messages(bob).first().single()
        assertEquals("7".repeat(64), row.rumorId)
        assertEquals(DmStatus.SENT, row.status)
        assertEquals(listOf(Triple(bob, "bye", listOf(DmRelaysRepository.FALLBACK))), r.repo.sent)
    }

    @Test
    fun `start fails sends left over from a previous process`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.store.upsert(DmMessage("pending-x", bob, true, "stuck", 100, 100, DmStatus.SENDING, null))
        r.store.upsert(DmMessage("5".repeat(64), bob, true, "done", 200, 200, DmStatus.SENT, null))
        r.dms.start()
        assertEquals(listOf(DmStatus.FAILED, DmStatus.SENT), r.store.messages(bob).first().map { it.status })

        // now retryable
        r.repo.sendResult = DmSendResult("6".repeat(64), 300, sentToPeer = true, sentToSelf = true)
        assertEquals(DmStatus.SENT, r.dms.retry("pending-x"))
        assertEquals(Triple(bob, "stuck", listOf(DmRelaysRepository.FALLBACK)), r.repo.sent.single())
    }

    @Test
    fun `an auth refusal holds lastSync until the relay authenticates, then one round catches up`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        val first = r.clock
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "auth-required: x"))
        assertTrue(r.dms.state.value.authBlocked)
        r.clock += 3_600
        r.dms.sync(interactive = false) // the fetch completes, but the inbox refuses the live subscription
        assertEquals(first, r.store.lastSync())
        val fetches = r.repo.fetchCalls.size
        r.clock += 60
        r.authOk.emit("wss://other.example.com") // another relay's AUTH heals nothing
        assertTrue(r.dms.state.value.authBlocked)
        assertEquals(fetches, r.repo.fetchCalls.size)
        r.authOk.emit(relay)
        assertFalse(r.dms.state.value.authBlocked)
        assertEquals(emptySet<String>(), r.dms.state.value.authFailed) // the Messages tab hint is gone
        assertEquals(fetches + 1, r.repo.fetchCalls.size)
        assertEquals(first - 2 * day, r.repo.fetchCalls.last().second) // the held window, not a new one
        assertEquals(r.clock, r.store.lastSync())
        assertEquals(listOf("sub1"), r.repo.unsubscribed) // the live subscription was renewed
    }

    @Test
    fun `while blocked the thirty-day window waits for the healed AUTH and then runs once`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.repo.fetchAuthRefused = mapOf(relay to 1L)
        r.repo.fetchCompleted = false
        r.dms.start() // the first run's 30-day window, refused
        assertEquals(setOf(relay), r.dms.state.value.authFailed)
        assertEquals(1, r.repo.fetchCalls.size)
        r.clock += 900
        assertTrue(r.dms.sync(interactive = false).isEmpty())
        assertEquals(1, r.repo.fetchCalls.size) // not fetched again while refused
        assertEquals(0L, r.store.lastSync())
        r.repo.fetchAuthRefused = emptyMap()
        r.repo.fetchCompleted = true
        r.clock += 900
        r.authOk.emit(relay)
        assertEquals(2, r.repo.fetchCalls.size)
        assertEquals(r.clock - 30 * day, r.repo.fetchCalls.last().second)
        assertEquals(r.clock, r.store.lastSync())
    }

    @Test
    fun `a refusal from a relay outside the own inbox list neither blocks nor stops lastSync`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        // a feed subscription refused by another relay, and one by the inbox relay for a subscription that is not the DM one
        r.closed.emit(NostrEngine.ClosedSubscription("wss://feed.example.com", "feed-1", "auth-required: x"))
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "feed-2", "restricted: paid feed"))
        assertFalse(r.dms.state.value.authBlocked)
        r.clock += 3_600
        r.dms.sync(interactive = false)
        assertEquals(r.clock, r.store.lastSync())
    }

    @Test
    fun `on the live subscription only an auth-required CLOSED blocks, matched trimmed and case-insensitively`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        val first = r.store.lastSync()
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "restricted: members only"))
        assertFalse(r.dms.state.value.authBlocked)
        assertEquals(emptySet<String>(), r.dms.state.value.authFailed)
        // the engine saw the CLOSED: the round is incomplete, lastSync holds
        r.clock += 3_600
        r.repo.fetchCompleted = false
        r.dms.sync(interactive = false)
        assertEquals(first, r.store.lastSync())
        assertFalse(r.dms.state.value.authBlocked)
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "  Auth-Required: x"))
        assertTrue(r.dms.state.value.authBlocked)
    }

    @Test
    fun `on the fetch path only an own relay's auth-required blocks, other refusals do not`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        // a restricted CLOSED from the own relay: the engine reports no AUTH refusal, the round is merely incomplete
        r.clock += 3_600
        r.repo.fetchCompleted = false
        r.dms.sync(interactive = false)
        assertFalse(r.dms.state.value.authBlocked)
        // an auth-required CLOSED from a foreign relay is not ours to count, and the round completes
        r.repo.fetchCompleted = true
        r.repo.fetchAuthRefused = mapOf("wss://feed.example.com" to 1L)
        r.dms.sync(interactive = false)
        assertFalse(r.dms.state.value.authBlocked)
        assertEquals(r.clock, r.store.lastSync())
        // an auth-required CLOSED from the own relay blocks
        val held = r.store.lastSync()
        r.clock += 3_600
        r.repo.fetchAuthRefused = mapOf(relay to 1L)
        r.dms.sync(interactive = false)
        assertTrue(r.dms.state.value.authBlocked)
        assertEquals(held, r.store.lastSync())
    }

    @Test
    fun `an AUTH OK processed before the parked CLOSED is marked leaves nothing blocked`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.repo.onSubscribe = { id ->
            r.authOkSeqs[relay] = 6 // the engine saw the OK after the CLOSED (seq 5), but the OK arrives first
            r.authOk.emit(relay) // not in authFailed yet: nothing to heal
            r.closed.emit(NostrEngine.ClosedSubscription(relay, id, "auth-required: x", seq = 5))
        }
        r.dms.start()
        assertFalse(r.dms.state.value.authBlocked)
        r.clock += 3_600
        r.dms.sync(interactive = false)
        assertEquals(r.clock, r.store.lastSync())
    }

    @Test
    fun `strfry's ERROR auth-required CLOSED on the live subscription blocks`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "ERROR: auth-required: requested filter requires authentication"))
        assertTrue(r.dms.state.value.authBlocked)
    }

    @Test
    fun `a live subscription refused before the AUTH OK is renewed once, also when the OK was processed first`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.repo.onSubscribe = { id ->
            if (id == "sub1") {
                r.authOkSeqs[relay] = 6 // the relay closed the REQ (seq 5) before it accepted our AUTH (seq 6)
                r.authOk.emit(relay) // processed first: nothing in authFailed to heal yet
                r.closed.emit(NostrEngine.ClosedSubscription(relay, id, "ERROR: auth-required: requested filter requires authentication", seq = 5))
            }
        }
        r.dms.start()
        // the relay dropped sub1: it is replaced by a REQ sent after the AUTH OK
        assertEquals(2, r.repo.subscribeCalls.size)
        assertEquals(listOf("sub1"), r.repo.unsubscribed)
        assertFalse(r.dms.state.value.authBlocked)
        // the same OK never renews twice
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub2", "ERROR: auth-required: x", seq = 5))
        assertEquals(2, r.repo.subscribeCalls.size)
    }

    @Test
    fun `a live subscription CLOSED after subscribing that the AUTH OK overtook is renewed`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        assertEquals(1, r.repo.subscribeCalls.size)
        r.authOkSeqs[relay] = 6
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "auth-required: x", seq = 5))
        assertEquals(2, r.repo.subscribeCalls.size)
        assertFalse(r.dms.state.value.authBlocked)
    }

    @Test
    fun `a renewal whose new REQ a reconnect refused before the AUTH OK was processed is renewed again`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "auth-required: x", seq = 3))
        assertTrue(r.dms.state.value.authBlocked)
        r.repo.onSubscribe = { id ->
            if (id == "sub2") {
                // the relay reconnects while the renewal runs: it refuses the new REQ (seq 5) before it
                // accepts the new AUTH (seq 6), and the OK is processed first
                r.authOkSeqs[relay] = 6
                r.authOk.emit(relay)
                r.closed.emit(NostrEngine.ClosedSubscription(relay, id, "ERROR: auth-required: x", seq = 5))
                yield() // the collector takes the frame while the new id is unknown (an early frame)
            }
        }
        r.authOkSeqs[relay] = 4
        r.authOk.emit(relay) // heals the first refusal: sub1 is renewed as sub2
        // the relay dropped sub2, so it is replaced by a REQ sent after the AUTH OK instead of staying dead
        assertEquals(3, r.repo.subscribeCalls.size)
        assertEquals(listOf("sub1", "sub2"), r.repo.unsubscribed)
        assertFalse(r.dms.state.value.authBlocked)
    }

    @Test
    fun `a fetch refusal older than the relay's AUTH OK does not block, a newer one does`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        r.authOkSeqs[relay] = 6 // the OK landed after the fetch's refusal (seq 5) but before the round marked it
        r.repo.fetchAuthRefused = mapOf(relay to 5L)
        r.clock += 3_600
        r.dms.sync(interactive = false)
        assertFalse(r.dms.state.value.authBlocked)
        assertEquals(r.clock, r.store.lastSync())
        r.repo.fetchAuthRefused = mapOf(relay to 7L)
        r.clock += 3_600
        r.dms.sync(interactive = false)
        assertTrue(r.dms.state.value.authBlocked)
    }

    @Test
    fun `a round that ran while the own list changed does not move lastSync`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.dms.start()
        val gate = CompletableDeferred<Unit>()
        r.repo.fetchGate = gate
        r.clock += 3_600
        val old = eager().async { r.dms.sync(interactive = false) } // waits for its relays
        r.repo.fetchGate = null
        r.clock += 60
        r.settings.setRelays(listOf("wss://new.example.com"))
        r.dms.ownRelaysChanged() // new generation: lastSync 0, restart, the new round completes at once
        val renewed = r.clock
        assertEquals(renewed, r.store.lastSync())
        gate.complete(Unit)
        assertTrue(old.await().isEmpty())
        assertEquals(renewed, r.store.lastSync()) // the old round (started an hour earlier) did not overwrite it
    }

    @Test
    fun `a wrap the silent signer failed three times waits for a user action or a healed AUTH`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val locked = wrapEvent()
        r.repo.unwrapOutcomes = { _, _ -> Unwrap.Locked }
        r.repo.fetched = listOf(locked)
        r.dms.start() // attempt 1
        r.dms.sync(interactive = false); r.dms.sync(interactive = false) // attempts 2 and 3
        assertEquals(3, r.repo.unwrapCalls.size)
        r.dms.sync(interactive = false)
        r.repo.wraps.emit(locked)
        assertEquals(3, r.repo.unwrapCalls.size) // parked: no further silent decryption
        assertEquals(1, r.store.pendingCount().first())

        r.store.setPendingSummaryShown(true)
        r.closed.emit(NostrEngine.ClosedSubscription(relay, "sub1", "auth-required: x"))
        r.authOk.emit(relay) // healed: parking and summary start over, the catch-up round tries it once more
        assertEquals(4, r.repo.unwrapCalls.size)
        assertFalse(r.store.pendingSummaryShown())
        assertEquals(1, r.store.pendingAttempts(locked.id().toHex()))

        r.db[locked.id().toHex()] = locked
        r.dms.drainPending() // a user action always tries interactively
        assertEquals(5, r.repo.unwrapCalls.size)
    }

    @Test
    fun `a rejected wrap is cached with its reason and never decrypted again`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        val bad = wrapEvent()
        r.repo.unwrapOutcomes = { _, _ -> Unwrap.Rejected }
        r.repo.fetched = listOf(bad)
        r.dms.start()
        assertEquals(DropReason.REJECTED, r.store.dropReason(bad.id().toHex()))
        r.repo.wraps.emit(bad)
        r.dms.sync(interactive = true)
        assertEquals(1, r.repo.unwrapCalls.size)
    }

    private fun rejection(seq: Long) = NostrEngine.AuthRejection(relay, "error: relay needs serviceUrl to be configured before AUTH can work", seq)

    @Test
    fun `a relay that rejects our AUTH stops blocking and is shown with its reason`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.repo.fetchCompleted = false
        r.repo.fetchAuthRefused = mapOf(relay to 5L)
        r.dms.start()
        assertEquals(setOf(relay), r.dms.state.value.authFailed) // CLOSED first: looks like any AUTH block
        assertTrue(r.dms.state.value.authBlocked)
        val before = r.repo.fetchCalls.size

        r.authRejections[relay] = rejection(6)
        r.authRejected.emit(rejection(6))

        assertTrue(r.dms.state.value.authFailed.isEmpty())
        assertFalse(r.dms.state.value.authBlocked)
        assertEquals(mapOf(relay to "error: relay needs serviceUrl to be configured before AUTH can work"), r.dms.state.value.authRejected)
        // the round the block held back runs once, without the relay that cannot be read
        assertEquals(before + 1, r.repo.fetchCalls.size)
        assertEquals(emptyList<String>(), r.repo.fetchCalls.last().first)
    }

    @Test
    fun `a rejection known before the refusal marks the relay rejected, never blocked`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.authRejections[relay] = rejection(4)
        r.repo.fetchCompleted = false
        r.repo.fetchAuthRefused = mapOf(relay to 5L)
        r.dms.start()
        assertTrue(r.dms.state.value.authFailed.isEmpty())
        assertEquals(setOf(relay), r.dms.state.value.authRejected.keys)
    }

    @Test
    fun `the other inbox relays are fetched while one rejects, and lastSync moves again`() = runTest {
        val other = "wss://inbox.example"
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay, other)))
        r.settings.setRelays(listOf(relay, other))
        r.authRejections[relay] = rejection(4)
        r.repo.fetchCompleted = false
        r.repo.fetchAuthRefused = mapOf(relay to 5L)
        r.dms.start() // first round still asks both and learns of the rejection
        assertEquals(0L, r.store.lastSync())

        r.repo.fetchCompleted = true
        r.repo.fetchAuthRefused = emptyMap()
        r.dms.sync(interactive = false)
        assertEquals(listOf(other), r.repo.fetchCalls.last().first)
        assertEquals(r.clock, r.store.lastSync())
    }

    @Test
    fun `with only a rejecting relay nothing is fetched and lastSync stays`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.authRejections[relay] = rejection(4)
        r.repo.fetchCompleted = false
        r.repo.fetchAuthRefused = mapOf(relay to 5L)
        r.dms.start()
        assertTrue(r.dms.sync(interactive = false).isEmpty())
        assertEquals(emptyList<String>(), r.repo.fetchCalls.last().first)
        assertEquals(0L, r.store.lastSync())
        assertFalse(r.dms.state.value.syncing)
    }

    @Test
    fun `a rejected relay that accepts an AUTH later is read again`() = runTest {
        val r = Rig(eager(), own = OwnDmRelays.Found(listOf(relay)))
        r.authRejections[relay] = rejection(4)
        r.repo.fetchCompleted = false
        r.repo.fetchAuthRefused = mapOf(relay to 5L)
        r.dms.start()
        val subs = r.repo.subscribeCalls.size

        r.authRejections.remove(relay)
        r.authOkSeqs[relay] = 9
        r.repo.fetchCompleted = true
        r.repo.fetchAuthRefused = emptyMap()
        r.authOk.emit(relay)

        assertTrue(r.dms.state.value.authRejected.isEmpty())
        assertEquals(subs + 1, r.repo.subscribeCalls.size)
        assertEquals(listOf(relay), r.repo.fetchCalls.last().first)
    }
}
