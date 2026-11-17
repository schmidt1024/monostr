package com.monostr.app.ui

import com.monostr.app.data.PendingTip
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.tips.PaymentInfo
import com.monostr.tips.Watcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * shortwavesurfer2009 2026-11-17: an anonymous tip to a note stayed «Waiting for payment» although the watcher had
 * published its receipt, because the tipper's relays never carry it. Own pending note tips ask the tip's watcher relays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnNoteTipReceiptsTest {
    private val recipient = "c".repeat(64)
    private val note = "1".repeat(64)
    private val watcherPk = "d".repeat(64)
    private val profiles = FakeProfiles(emptyMap())
    private val info = PaymentInfo.Enabled(MoneroKeys.generate().address(Network.MAINNET), Watcher.Http("https://w.example", watcherPk))

    private fun TestScope.run(tip: PendingTip, found: Set<String>): Pair<FakeTips, FakePendingTips> {
        val tips = FakeTips(infos = mapOf(recipient to info)).apply { watcherReceipts = found }
        val pending = FakePendingTips().apply { state.value = listOf(tip) }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        TipDecorations(tips, pending, profiles, FakePublish(), scope, ownReceiptsEveryMs = TipDecorations.OWN_RECEIPTS_EVERY_MS) { 10_000 }.start()
        advanceTimeBy(TipDecorations.OWN_RECEIPTS_FIRST_MS + 1); runCurrent()
        scope.cancel()
        return tips to pending
    }

    @Test
    fun `an anonymous note tip settles from its watcher relays over its own connection`() = runTest {
        val tip = PendingTip("a".repeat(64), note, 5, 9_990, recipient, anonEvent = "{}", anonRelays = listOf("wss://w-relay.example"), anonDelivered = true)
        val (tips, pending) = run(tip, setOf("a".repeat(64)))
        assertEquals(listOf(listOf("wss://w-relay.example") to true), tips.receiptLookups)
        assertTrue(pending.state.value.isEmpty(), "settled")
    }

    @Test
    fun `a public note tip asks the watcher relays over the session`() = runTest {
        val tip = PendingTip("b".repeat(64), note, 5, 9_990, recipient, watcherRelays = listOf("wss://w-relay.example"), watcherDelivered = true)
        val (tips, pending) = run(tip, setOf("b".repeat(64)))
        assertEquals(listOf(listOf("wss://w-relay.example") to false), tips.receiptLookups)
        assertTrue(pending.state.value.isEmpty())
    }

    @Test
    fun `without a receipt the tip keeps waiting`() = runTest {
        val tip = PendingTip("e".repeat(64), note, 5, 9_990, recipient, anonEvent = "{}", anonRelays = listOf("wss://w-relay.example"))
        val (_, pending) = run(tip, emptySet())
        assertEquals(1, pending.state.value.size)
    }

    @Test
    fun `two anonymous tips to one recipient are never asked about together`() = runTest {
        val a = PendingTip("a".repeat(64), note, 5, 9_990, recipient, anonEvent = "{}", anonRelays = listOf("wss://w-relay.example"), anonDelivered = true)
        val b = PendingTip("b".repeat(64), "2".repeat(64), 5, 9_991, recipient, anonEvent = "{}", anonRelays = listOf("wss://w-relay.example"), anonDelivered = true)
        val tips = FakeTips(infos = mapOf(recipient to info))
        val pending = FakePendingTips().apply { state.value = listOf(a, b) }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        TipDecorations(tips, pending, profiles, FakePublish(), scope, ownReceiptsEveryMs = TipDecorations.OWN_RECEIPTS_EVERY_MS) { 10_000 }.start()
        advanceTimeBy(TipDecorations.OWN_RECEIPTS_FIRST_MS + 1); runCurrent()
        scope.cancel()
        assertEquals(2, tips.receiptLookups.size, "one question per anonymous tip")
    }

    @Test
    fun `an anonymous tip older than the window is not asked about`() = runTest {
        val old = PendingTip("a".repeat(64), note, 5, 10_000 - TipDecorations.OWN_ANON_ASK_WINDOW - 1, recipient, anonEvent = "{}", anonRelays = listOf("wss://w-relay.example"))
        val (tips, _) = run(old, emptySet())
        assertTrue(tips.receiptLookups.isEmpty())
    }
}
