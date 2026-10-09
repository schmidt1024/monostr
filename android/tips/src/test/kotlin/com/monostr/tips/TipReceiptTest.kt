package com.monostr.tips

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TipReceiptTest {
    private val watcher = FakeSigner("d".repeat(64))
    private val note = "b".repeat(64)
    private val recipient = "c".repeat(64)
    private val sender = "a".repeat(64)
    private val intentId = "e".repeat(64)

    private fun unsigned() = TipReceipt.build(note, recipient, sender, 4_900_000_000L, intentId, TipType.BOOST, createdAt = 1700000100)

    @Test
    fun `build produces protocol tags`() {
        val u = unsigned()
        assertEquals(Kinds.TIP_RECEIPT, u.kind)
        assertEquals("", u.content)
        assertEquals(
            listOf(
                listOf("e", note), listOf("p", recipient), listOf("P", sender),
                listOf("amount", "4900000000"), listOf("intent", intentId), listOf("type", "boost"),
            ),
            u.tags,
        )
    }

    @Test
    fun `roundtrip`() = runTest {
        val signed = watcher.sign(unsigned())
        val r = TipReceipt.parse(signed)!!
        assertEquals(signed.id, r.id)
        assertEquals(watcher.pubkey, r.watcherPubkey)
        assertEquals(note, r.noteId)
        assertEquals(recipient, r.recipientPubkey)
        assertEquals(sender, r.senderPubkey)
        assertEquals(4_900_000_000L, r.amount)
        assertEquals(intentId, r.intentId)
        assertEquals(TipType.BOOST, r.type)
        assertEquals(1700000100, r.createdAt)
    }

    @Test
    fun `parse accepts tip type`() = runTest {
        val good = watcher.sign(unsigned())
        fun set(tag: String, value: String) = good.copy(tags = good.tags.map { if (it[0] == tag) listOf(tag, value) else it })

        val r = TipReceipt.parse(set("type", "tip"))!!
        assertEquals(TipType.TIP, r.type)
    }

    @Test
    fun `parse rejects malformed`() = runTest {
        val good = watcher.sign(unsigned())
        fun drop(tag: String) = good.copy(tags = good.tags.filter { it[0] != tag })
        fun set(tag: String, value: String) = good.copy(tags = good.tags.map { if (it[0] == tag) listOf(tag, value) else it })

        assertNull(TipReceipt.parse(good.copy(kind = Kinds.TIP_INTENT)))
        for (t in listOf("p", "amount", "intent", "type")) assertNull(TipReceipt.parse(drop(t)), t)
        assertNull(TipReceipt.parse(set("e", "short")))
        assertNull(TipReceipt.parse(set("amount", "0")))
        assertNull(TipReceipt.parse(set("amount", "-1")))
        assertNull(TipReceipt.parse(set("P", "A".repeat(64))))
        assertNull(TipReceipt.parse(set("intent", "short")))
        assertNull(TipReceipt.parse(set("type", "zap")))
    }

    @Test
    fun `a receipt without e is a profile tip, one without P an anonymous tip`() = runTest {
        val profile = TipReceipt.parse(watcher.sign(TipReceipt.build(null, recipient, sender, 5, intentId, TipType.TIP, 1)))!!
        assertNull(profile.noteId)
        assertEquals(sender, profile.senderPubkey)
        val anonymous = TipReceipt.parse(watcher.sign(TipReceipt.build(note, recipient, null, 5, intentId, TipType.TIP, 1)))!!
        assertEquals(note, anonymous.noteId)
        assertNull(anonymous.senderPubkey)
        val both = TipReceipt.build(null, recipient, null, 5, intentId, TipType.TIP, 1)
        assertEquals(listOf("p", "amount", "intent", "type"), both.tags.map { it[0] })
    }
}
