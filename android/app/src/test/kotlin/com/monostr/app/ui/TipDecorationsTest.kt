package com.monostr.app.ui

import com.monostr.app.data.PendingTip
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.TipSummary
import com.monostr.nostr.repo.Tipper
import com.monostr.tips.TipReceipt
import com.monostr.tips.TipType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TipDecorationsTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val watcher = "c".repeat(64)
    private val profiles = FakeProfiles(mapOf(bob to Profile(bob, "bob", "Bob", null, null, null)))

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun receipt(noteId: String, intentId: String, amount: Long, at: Long) =
        TipReceipt("r$at".padEnd(64, '0'), watcher, noteId, alice, bob, amount, intentId, TipType.LIKE, at)

    @Test
    fun `tracks the original of reposts, decorates with sums and settles pending tips on receipt`() = runTest {
        val tips = FakeTips()
        val pending = FakePendingTips()
        val d = TipDecorations(tips, pending, profiles, FakePublish(), eager()) { 5_000 }
        d.start()
        val original = note('1', alice, 1000)
        val repost = repostOf(original, bob, 2000)
        d.track(listOf(repost, note('2', bob, 3000)))
        advanceUntilIdle()
        assertEquals(mapOf(original.id to alice, note('2', bob).id to bob), tips.observed.last())
        val ui = NoteUi(repost, Profile.empty(bob), Profile.empty(alice))
        assertNull(d.decorate(ui).tips)
        assertFalse(d.decorate(ui).pendingTip)
        val intentId = "e".repeat(64)
        pending.add(PendingTip(intentId, original.id, 5, 4_000))
        pending.add(PendingTip("f".repeat(64), original.id, 5, 4_000 - 86_400)) // expired: never shown
        advanceUntilIdle()
        assertTrue(d.decorate(ui).pendingTip)
        assertEquals(setOf(original.id), d.pendingNotes.value)
        tips.receipts.value = mapOf(original.id to TipSummary(8, 2, listOf(receipt(original.id, intentId, 5, 4_500), receipt(original.id, "9".repeat(64), 3, 4_400))))
        advanceUntilIdle()
        assertEquals(8L, d.decorate(ui).tips?.total)
        assertEquals(2, d.decorate(ui).tips?.count)
        assertFalse(d.decorate(ui).pendingTip, "a receipt for the pending intent settles it")
        assertEquals(listOf("f".repeat(64)), pending.state.value.map { it.intentId }, "only the (expired) other one stays stored")
    }

    @Test
    fun `tippers resolve sender names through the profile repository`() = runTest {
        val tips = FakeTips()
        val original = note('1', alice, 1000)
        tips.tippers = mapOf(original.id to listOf(Tipper(receipt(original.id, "e".repeat(64), 7, 100), "danke")))
        val d = TipDecorations(tips, FakePendingTips(), profiles, FakePublish(), eager()) { 5_000 }
        val list = d.tippers(repostOf(original, bob, 2000))
        assertEquals(1, list.size)
        assertEquals("Bob", list[0].name)
        assertEquals(7L, list[0].amount)
        assertEquals("danke", list[0].comment)
    }

    @Test
    fun `active pending intents are re-sent once on start`() = runTest {
        val pending = FakePendingTips()
        val active = "1234".padStart(64, 'e')
        pending.add(PendingTip(active, note('1', alice).id, 5, 4_000))
        pending.add(PendingTip("9876".padStart(64, 'f'), note('1', alice).id, 5, 4_000 - 86_400)) // expired: not re-sent
        val publish = FakePublish()
        val d = TipDecorations(FakeTips(), pending, profiles, publish, eager()) { 5_000 }
        d.start()
        advanceUntilIdle()
        assertEquals(listOf("resend:1234"), publish.calls)
    }

    @Test
    fun `own notes cannot be tipped`() = runTest {
        val d = TipDecorations(FakeTips(), FakePendingTips(), profiles, FakePublish(), eager(), selfPubkey = alice) { 5_000 }
        val own = NoteUi(note('1', alice), Profile.empty(alice), Profile.empty(alice))
        val foreign = NoteUi(note('2', bob), Profile.empty(bob), Profile.empty(bob))
        val ownReposted = NoteUi(repostOf(note('3', alice), bob, 2000), Profile.empty(bob), Profile.empty(alice))
        assertFalse(d.decorate(own).canTip)
        assertTrue(d.decorate(foreign).canTip)
        assertFalse(d.decorate(ownReposted).canTip, "a repost counts for its original")
    }

    @Test
    fun `a pending profile tip marks no note`() = runTest {
        val pending = FakePendingTips()
        val d = TipDecorations(FakeTips(), pending, profiles, FakePublish(), eager()) { 5_000 }
        d.start()
        pending.add(PendingTip("e".repeat(64), null, 5, 4_000, recipient = alice))
        advanceUntilIdle()
        assertTrue(d.pendingNotes.value.isEmpty())
    }

    @Test
    fun `an anonymous pending intent is re-sent over the anonymous path only`() = runTest {
        val pending = FakePendingTips()
        pending.add(PendingTip("1234".padStart(64, 'e'), note('1', alice).id, 5, 4_000, recipient = alice, anonEvent = """{"id":"x"}""", anonRelays = listOf("wss://w.example")))
        pending.add(PendingTip("5678".padStart(64, 'e'), null, 5, 4_000, recipient = alice))
        val tips = FakeTips()
        val publish = FakePublish()
        val d = TipDecorations(tips, pending, profiles, publish, eager()) { 5_000 }
        d.start()
        advanceUntilIdle()
        assertEquals(listOf("""{"id":"x"}""" to listOf("wss://w.example")), tips.anonymousResends)
        assertEquals(listOf("resend:5678"), publish.calls, "the public profile tip goes the normal way, the anonymous one never does")
    }

    private fun anonymousPending(delivered: Boolean = false) =
        PendingTip("1234".padStart(64, 'e'), null, 5, 4_000, recipient = alice, anonEvent = """{"id":"x"}""", anonRelays = listOf("wss://w.example"), anonDelivered = delivered)

    @Test
    fun `a delivered anonymous pending intent is not re-sent`() = runTest {
        val pending = FakePendingTips()
        pending.add(anonymousPending(delivered = true))
        val tips = FakeTips()
        val publish = FakePublish()
        TipDecorations(tips, pending, profiles, publish, eager()) { 5_000 }.start()
        advanceUntilIdle()
        assertTrue(tips.anonymousResends.isEmpty(), "every resend would be another connection from this device for the same intent")
        assertTrue(publish.calls.isEmpty())
    }

    @Test
    fun `an undelivered anonymous pending intent is re-sent once and marked delivered`() = runTest {
        val pending = FakePendingTips()
        pending.add(anonymousPending())
        val tips = FakeTips()
        val publish = FakePublish()
        TipDecorations(tips, pending, profiles, publish, eager()) { 5_000 }.start()
        advanceUntilIdle()
        assertEquals(1, tips.anonymousResends.size)
        assertTrue(publish.calls.isEmpty())
        assertTrue(pending.state.value.single().anonDelivered)
    }

    @Test
    fun `an anonymous resend no relay accepted stays undelivered`() = runTest {
        val pending = FakePendingTips()
        pending.add(anonymousPending())
        val tips = FakeTips(relaysOk = false)
        TipDecorations(tips, pending, profiles, FakePublish(), eager()) { 5_000 }.start()
        advanceUntilIdle()
        assertEquals(1, tips.anonymousResends.size)
        assertFalse(pending.state.value.single().anonDelivered)
    }

    @Test
    fun `an anonymous tipper has no name`() = runTest {
        val tips = FakeTips()
        val original = note('1', alice, 1000)
        val anonymous = TipReceipt("r1".padEnd(64, '0'), watcher, original.id, alice, null, 7, "e".repeat(64), TipType.TIP, 100)
        tips.tippers = mapOf(original.id to listOf(Tipper(anonymous, ""), Tipper(receipt(original.id, "f".repeat(64), 3, 90), "")))
        val d = TipDecorations(tips, FakePendingTips(), profiles, FakePublish(), eager()) { 5_000 }
        val list = d.tippers(original)
        assertNull(list[0].name)
        assertEquals("Bob", list[1].name)
    }

    @Test
    fun `own notes are marked as own, also behind a repost by someone else`() = runTest {
        val d = TipDecorations(FakeTips(), FakePendingTips(), profiles, FakePublish(), eager(), selfPubkey = alice) { 5_000 }
        val mine = note('1', alice)
        val foreign = note('2', bob)
        fun ui(n: com.monostr.nostr.model.Note) = NoteUi(n, Profile.empty(n.author), Profile.empty(n.author))
        assertTrue(d.decorate(ui(mine)).isOwn)
        assertFalse(d.decorate(ui(foreign)).isOwn)
        assertTrue(d.decorate(ui(repostOf(mine, by = bob, createdAt = 5))).isOwn)
    }
}
