package com.monostr.app.ui

import com.monostr.app.net.SendGate
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.app.ui.tips.WatcherReceipts
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.NostrTipsRepository
import com.monostr.nostr.repo.TipSummary
import com.monostr.nostr.repo.WatcherFetch
import com.monostr.tips.PaymentInfo
import com.monostr.tips.TipReceipt
import com.monostr.tips.TipType
import com.monostr.tips.Watcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Backlog 97: tip counts from the note author's watcher relays, for viewers whose relays never carry the receipts. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatcherReceiptsDecorationsTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val addr = MoneroKeys.generate().address(Network.MAINNET)
    private val httpInfo = PaymentInfo.Enabled(addr, Watcher.Http("https://w.example", "d".repeat(64)))
    private val profiles = FakeProfiles(emptyMap())

    private lateinit var scope: CoroutineScope
    private lateinit var d: TipDecorations
    private lateinit var w: WatcherReceipts
    private var clockMs = 0L

    private fun receipt(noteId: String, intentId: String, amount: Long?, at: Long) =
        TipReceipt("r$at".padEnd(64, '0'), "d".repeat(64), noteId, bob, alice, amount, intentId, TipType.LIKE, at)

    private fun TestScope.rig(infos: Map<String, PaymentInfo>, send: SendGate = SendGate.OPEN, enabled: Boolean = true) =
        rig(infos, send, enabledFn = { enabled })

    private fun TestScope.rig(infos: Map<String, PaymentInfo>, send: SendGate = SendGate.OPEN, enabledFn: suspend () -> Boolean): Pair<FakeTips, FakeWatcherGateway> {
        val tips = FakeTips(infos)
        val gw = FakeWatcherGateway()
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        w = WatcherReceipts(gw, { true }) { clockMs }
        d = TipDecorations(tips, FakePendingTips(), profiles, FakePublish(), scope, sendGate = send, watcherReceipts = w, authorRelaysEnabled = enabledFn) { 5_000 }
        d.start()
        return tips to gw
    }

    private fun TestScope.settle() { advanceTimeBy(TipDecorations.WATCHER_DEBOUNCE_MS + 1); runCurrent() }

    @Test
    fun `the tracked notes of authors with a watcher are asked there once, grouped`() = runTest {
        val (tips, gw) = rig(mapOf(alice to httpInfo, bob to httpInfo)) // bob: same watcher, same relays
        val n1 = note('1', alice); val n2 = note('2', bob); val n3 = note('3', "e".repeat(64)) // no 10037
        d.track(listOf(n1, n2, n3)); settle()
        assertEquals(listOf(Triple(listOf("wss://watcher-relay.example"), listOf(n1.id, n2.id), "d".repeat(64))), tips.watcherFetches)
        assertEquals(1, gw.infoCalls)
        assertTrue("e".repeat(64) in tips.cachedCalls, "a local look only, nothing fetched for it")
    }

    @Test
    fun `two watchers make two groups`() = runTest {
        val personal = PaymentInfo.Enabled(addr, Watcher.Personal("f".repeat(64), listOf("wss://p.example")))
        val (tips, _) = rig(mapOf(alice to httpInfo, bob to personal))
        d.track(listOf(note('1', alice), note('2', bob))); settle()
        assertEquals(setOf(listOf("wss://watcher-relay.example"), listOf("wss://p.example")), tips.watcherFetches.map { it.first }.toSet())
    }

    @Test
    fun `with the author relays setting off nothing is asked`() = runTest {
        val (tips, gw) = rig(mapOf(alice to httpInfo), enabled = false)
        d.track(listOf(note('1', alice))); settle()
        assertTrue(tips.watcherFetches.isEmpty()); assertEquals(0, gw.infoCalls)
    }

    @Test
    fun `the setting is read at use time`() = runTest {
        var on = false
        val (tips, _) = rig(mapOf(alice to httpInfo), enabledFn = { on })
        d.track(listOf(note('1', alice))); settle()
        on = true; d.track(listOf(note('1', alice), note('2', alice))); settle()
        assertEquals(listOf(listOf(note('1', alice).id, note('2', alice).id)), tips.watcherFetches.map { it.second })
    }

    @Test
    fun `with Tor not connected nothing is asked and nothing is marked`() = runTest {
        val tor = SendRig(connected = false)
        val (tips, gw) = rig(mapOf(alice to httpInfo), send = tor.send)
        d.track(listOf(note('1', alice))); settle()
        assertTrue(tips.watcherFetches.isEmpty()); assertEquals(0, gw.infoCalls)
        tor.connected.value = true
        d.track(listOf(note('1', alice), note('2', alice))); settle()
        assertEquals(listOf(listOf(note('1', alice).id, note('2', alice).id)), tips.watcherFetches.map { it.second })
    }

    @Test
    fun `a note is asked again only after five minutes, also from another screen`() = runTest {
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val other = TipDecorations(tips, FakePendingTips(), profiles, FakePublish(), scope, watcherReceipts = w, authorRelaysEnabled = { true }) { 5_000 }.also { it.start() }
        d.track(listOf(note('1', alice))); settle()
        other.track(listOf(note('1', alice))); settle()
        assertEquals(1, tips.watcherFetches.size)
        clockMs += WatcherReceipts.ASK_EVERY_MS
        other.track(listOf(note('1', alice), note('2', alice))); settle()
        assertEquals(listOf(note('1', alice).id, note('2', alice).id), tips.watcherFetches.last().second)
    }

    @Test
    fun `a failing watcher relay leaves the read-relay counts and is not retried at once`() = runTest {
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val n1 = note('1', alice)
        tips.receipts.value = mapOf(n1.id to TipSummary(3, 1, listOf(receipt(n1.id, "e".repeat(64), 3, 4_500))))
        tips.watcherFetchError = IllegalStateException("relay down")
        d.track(listOf(n1)); settle()
        assertEquals(3L, d.summaries.value[n1.id]?.total)
        d.track(listOf(n1, note('2', alice))); settle()
        assertEquals(listOf(note('2', alice).id), tips.watcherFetches.last().second, "n1 waits its five minutes")
    }

    @Test
    fun `after the fetch the stored sums of the asked notes are read again`() = runTest {
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val n1 = note('1', alice)
        d.track(listOf(n1)); settle()
        assertEquals(mapOf(n1.id to alice), tips.localObserved.last())
    }

    @Test
    fun `receipts the fetch stored show without a live subscription`() = runTest {
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val n1 = note('1', alice)
        tips.receiptsError = IllegalStateException("read relays down") // the live subscription ends at once
        tips.onWatcherFetch = { tips.receipts.value = mapOf(n1.id to TipSummary(7, 1, listOf(receipt(n1.id, "e".repeat(64), 7, 4_500)))) }
        d.track(listOf(n1)); settle()
        assertEquals(7L, d.summaries.value[n1.id]?.total)
    }

    @Test
    fun `a running round is not cancelled by a new set, the next round takes it`() = runTest {
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        tips.watcherFetchGate = gate // a slow round over Tor
        val n1 = note('1', alice); val n2 = note('2', alice)
        d.track(listOf(n1)); settle()
        d.track(listOf(n1, n2)); settle()
        assertEquals(1, tips.watcherFetches.size, "the second set waits for the running round")
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(listOf(n1.id), listOf(n2.id)), tips.watcherFetches.map { it.second })
        assertEquals(2, tips.watcherFetchesDone, "the first fetch ran to its end")
    }

    @Test
    fun `an author without a 10037 is looked at once per five minutes`() = runTest {
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val carol = "e".repeat(64)
        d.track(listOf(note('3', carol))); settle()
        d.track(listOf(note('3', carol), note('1', alice))); settle()
        assertEquals(1, tips.cachedCalls.count { it == carol })
    }

    @Test
    fun `only live add slices are asked, not every target add ever saw`() = runTest {
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val all = (0 until 480).map { i -> Note("%064x".format(i), alice, "n$i", 10_000L - i, 1, null, null, null, emptyList()) }
        all.chunked(40).forEach { d.add(it); runCurrent() }
        settle()
        val asked = tips.watcherFetches.flatMap { it.second }.toSet()
        assertTrue(asked.size <= TipDecorations.MAX_LIVE_GROUPS * NostrTipsRepository.MAX_NOTES, "asked ${asked.size}")
        assertTrue(all.first().id in asked && all.last().id in asked)
    }

    @Test
    fun `no free foreign-relay slot asks again after the short retry, own relays only waits five minutes`() = runTest {
        // final review I2
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val n1 = note('1', alice); val n2 = note('2', alice); val n3 = note('3', alice)
        tips.watcherFetchResult = WatcherFetch.NoSlot
        d.track(listOf(n1)); settle()
        clockMs += WatcherReceipts.RETRY_MS - 1
        d.track(listOf(n1, n2)); settle()
        assertEquals(listOf(listOf(n1.id), listOf(n2.id)), tips.watcherFetches.map { it.second }, "n1 waits its retry")
        clockMs += 1
        tips.watcherFetchResult = WatcherFetch.OwnRelaysOnly
        d.track(listOf(n1, n2, n3)); settle()
        assertEquals(listOf(n1.id, n3.id), tips.watcherFetches.last().second, "n1 once its retry is due; n2 waits the retry")
        clockMs += WatcherReceipts.RETRY_MS
        d.track(listOf(n1, n2, n3, note('4', alice))); settle()
        assertEquals(listOf(n2.id, note('4', alice).id), tips.watcherFetches.last().second, "own relays only: n1 and n3 wait five minutes")
    }

    @Test
    fun `the rounds of several screens fetch one at a time`() = runTest {
        // final review I2: together they must not take the whole foreign-relay budget
        val (tips, _) = rig(mapOf(alice to httpInfo))
        val other = TipDecorations(tips, FakePendingTips(), profiles, FakePublish(), scope, watcherReceipts = w, authorRelaysEnabled = { true }) { 5_000 }.also { it.start() }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        tips.watcherFetchGate = gate
        d.track(listOf(note('1', alice))); other.track(listOf(note('2', alice))); settle()
        assertEquals(1, tips.watcherFetches.size, "the second screen waits for the running fetch")
        gate.complete(Unit); runCurrent()
        assertEquals(setOf(listOf(note('1', alice).id), listOf(note('2', alice).id)), tips.watcherFetches.map { it.second }.toSet())
    }

    @Test
    fun `Tor connecting starts a round without a new set`() = runTest {
        // final review M1
        val tor = SendRig(connected = false)
        val (tips, _) = rig(mapOf(alice to httpInfo), send = tor.send)
        d.track(listOf(note('1', alice))); settle()
        assertTrue(tips.watcherFetches.isEmpty())
        tor.connected.value = true; settle()
        assertEquals(listOf(listOf(note('1', alice).id)), tips.watcherFetches.map { it.second })
    }

    @Test
    fun `a failing settings read ends the round, not the rounds after it`() = runTest {
        // final review M6
        var fail = true
        val (tips, _) = rig(mapOf(alice to httpInfo), enabledFn = { if (fail) throw java.io.IOException("datastore") else true })
        d.track(listOf(note('1', alice))); settle()
        assertTrue(tips.watcherFetches.isEmpty())
        fail = false
        d.track(listOf(note('1', alice), note('2', alice))); settle()
        assertEquals(listOf(listOf(note('1', alice).id, note('2', alice).id)), tips.watcherFetches.map { it.second })
    }
}
