package com.monostr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TipStoresTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `presets parse decimal xmr, reject garbage and format without float artifacts`() {
        assertEquals(listOf(100_000_000L, 1_000_000_000L, 10_000_000_000L), Presets.DEFAULT)
        assertEquals(listOf(1_000_000_000L, 5_000_000_000L, 20_000_000L), Presets.parseXmr(listOf("0.001", "0,005", "0.00002")))
        assertNull(Presets.parseXmr(listOf("abc")))
        assertNull(Presets.parseXmr(listOf("0")))
        assertNull(Presets.parseXmr(listOf("-1")))
        assertNull(Presets.parseXmr(listOf("0.0000000000001")))
        assertNull(Presets.parseXmr(emptyList()))
        assertEquals(listOf(1_000_000_000L), Presets.parseXmr(listOf("0.001", " ", "0.001")), "blanks dropped, duplicates merged")
        assertEquals("0.005", Presets.format(5_000_000_000))
        assertEquals("1.5", Presets.format(1_500_000_000_000))
    }

    @Test
    fun `pending tips expire after 24 hours and survive a json round trip`() {
        val a = PendingTip("a".repeat(64), "1".repeat(64), 1_000_000_000, 1000)
        val b = PendingTip("b".repeat(64), "2".repeat(64), 2_000_000_000, 90_000)
        assertEquals(listOf(b), PendingTips.active(listOf(a, b), now = 1000 + 86_400))
        assertEquals(listOf(a, b), PendingTips.active(listOf(a, b), now = 1000 + 86_399))
        assertEquals(listOf(a, b), PendingTips.decode(PendingTips.encode(listOf(a, b))))
        assertEquals(emptyList<PendingTip>(), PendingTips.decode("not json"))
        assertEquals(emptyList<PendingTip>(), PendingTips.decode(""))
    }

    @Test
    fun `pending tips written before 0_8 still decode, and the new fields survive a round trip`() {
        val old = """[{"intentId":"${"a".repeat(64)}","noteId":"${"1".repeat(64)}","amount":5,"createdAt":1000}]"""
        assertEquals(listOf(PendingTip("a".repeat(64), "1".repeat(64), 5, 1000)), PendingTips.decode(old))
        val profile = PendingTip("b".repeat(64), null, 7, 2000, recipient = "c".repeat(64), anonEvent = """{"id":"x"}""", anonRelays = listOf("wss://w.example"))
        assertEquals(listOf(profile), PendingTips.decode(PendingTips.encode(listOf(profile))))
    }

    @Test
    fun `datastore backed stores round trip presets, setup, markers and pending tips`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("test.preferences_pb").toFile() }
        val settings = PrefsTipSettingsStore(store)
        assertEquals(Presets.DEFAULT, settings.presets.first())
        settings.setPresets(listOf(3_000_000_000L, 1_000_000_000L))
        assertEquals(listOf(1_000_000_000L, 3_000_000_000L), settings.presets.first(), "stored sorted ascending")
        assertNull(settings.setup.first())
        val setup = MoneroSetup("4addr", "https://w.example", "c".repeat(64), "mainnet", 123)
        settings.setSetup(setup)
        assertEquals(setup, settings.setup.first())
        settings.setSetup(null)
        assertNull(settings.setup.first())
        assertFalse(settings.onboardingSeen.first())
        settings.setOnboardingSeen()
        assertTrue(settings.onboardingSeen.first())
        assertEquals(0L, settings.notificationsReadAt.first())
        settings.setNotificationsReadAt(777)
        assertEquals(777L, settings.notificationsReadAt.first())
        assertEquals(0L, settings.lastTipNotifiedAt())
        settings.setLastTipNotifiedAt(888)
        assertEquals(888L, settings.lastTipNotifiedAt())

        assertTrue(settings.anonymous.first(), "anonymous is the default")
        settings.setAnonymous(false)
        assertFalse(settings.anonymous.first())
        settings.setAnonymous(true)
        assertTrue(settings.anonymous.first())
        val pending = PrefsPendingTipStore(store)
        val a = PendingTip("a".repeat(64), "1".repeat(64), 1_000_000_000, 1000)
        val b = PendingTip("b".repeat(64), "2".repeat(64), 2_000_000_000, 90_000)
        pending.add(a); pending.add(b); pending.add(a)
        assertEquals(listOf(a, b), pending.pending.first(), "same intent id is not stored twice")
        pending.prune(now = 1000 + 86_400)
        assertEquals(listOf(b), pending.pending.first())
        pending.remove(listOf(b.intentId))
        assertTrue(pending.pending.first().isEmpty())
        scope.cancel()
    }

    @Test
    fun `markDelivered flips the flag of that entry only and survives a re-read`() = runTest {
        val file = dir.resolve("delivered.preferences_pb").toFile()
        val anon = PendingTip("a".repeat(64), null, 5, 1000, recipient = "c".repeat(64), anonEvent = """{"id":"x"}""", anonRelays = listOf("wss://w.example"))
        val other = anon.copy(intentId = "b".repeat(64))
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val pending = PrefsPendingTipStore(PreferenceDataStoreFactory.create(scope = first) { file })
        pending.add(anon); pending.add(other)
        pending.markDelivered(anon.intentId)
        pending.markDelivered("d".repeat(64)) // absent: no-op
        assertEquals(listOf(anon.copy(anonDelivered = true), other), pending.pending.first())
        first.cancel()
        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val reread = PrefsPendingTipStore(PreferenceDataStoreFactory.create(scope = second) { file })
        assertEquals(listOf(anon.copy(anonDelivered = true), other), reread.pending.first())
        second.cancel()
    }

    @Test
    fun `an entry written before anonDelivered decodes as not delivered`() {
        val old = """[{"intentId":"${"a".repeat(64)}","noteId":null,"amount":5,"createdAt":1000,"recipient":"${"c".repeat(64)}","anonEvent":"{}","anonRelays":["wss://w.example"]}]"""
        assertFalse(PendingTips.decode(old).single().anonDelivered)
    }
}
