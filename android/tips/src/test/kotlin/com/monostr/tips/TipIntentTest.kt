package com.monostr.tips

import com.monostr.tips.event.Event
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TipIntentTest {
    private val note = "b".repeat(64)
    private val recipient = "c".repeat(64)
    private val pid = PaymentId.parse("0123456789abcdef")!!
    private val signer = FakeSigner("a".repeat(64))

    private fun intent() = TipIntent.create(note, recipient, 5_000_000_000L, TipType.LIKE, "Tip für dich & mehr", pid, createdAt = 1700000000)

    @Test
    fun `create sets 24h expiration and builds the reference event`() = runTest {
        val i = intent()
        assertEquals(1700086400, i.expiration)
        val signed = signer.sign(i.toUnsignedEvent())
        assertEquals("d8cdc9bea0c5953d8026dbe87753397e720b5a45d4f79beb69f0fb2e65c88602", signed.id)
        assertEquals(Kinds.TIP_INTENT, signed.kind)
    }

    @Test
    fun `roundtrip through signed event`() = runTest {
        val i = intent()
        assertEquals(i, TipIntent.parse(signer.sign(i.toUnsignedEvent())))
    }

    @Test
    fun `create validates inputs`() {
        assertThrows(IllegalArgumentException::class.java) { TipIntent.create("b".repeat(63), recipient, 1, TipType.LIKE, "", pid, 1) }
        assertThrows(IllegalArgumentException::class.java) { TipIntent.create(note, "C".repeat(64), 1, TipType.LIKE, "", pid, 1) }
        assertThrows(IllegalArgumentException::class.java) { TipIntent.create(note, recipient, 0, TipType.LIKE, "", pid, 1) }
    }

    @Test
    fun `isExpired`() {
        val i = intent()
        assertFalse(i.isExpired(1700086399))
        assertTrue(i.isExpired(1700086400))
    }

    @Test
    fun `parse rejects malformed events`() = runTest {
        val good = signer.sign(intent().toUnsignedEvent())
        fun with(mutate: (List<List<String>>) -> List<List<String>>, kind: Int = Kinds.TIP_INTENT): Event =
            good.copy(kind = kind, tags = mutate(good.tags))

        assertNull(TipIntent.parse(with(mutate = { it }, kind = 1)))
        assertNull(TipIntent.parse(with(mutate = { tags -> tags.map { if (it[0] == "amount") listOf("amount", "0") else it } })))
        assertNull(TipIntent.parse(with(mutate = { tags -> tags.map { if (it[0] == "amount") listOf("amount", "1.5") else it } })))
        assertNull(TipIntent.parse(with(mutate = { tags -> tags.map { if (it[0] == "pid") listOf("pid", "0123456789abcde") else it } })))
        assertNull(TipIntent.parse(with(mutate = { tags -> tags.map { if (it[0] == "type") listOf("type", "zap") else it } })))
        assertNull(TipIntent.parse(with(mutate = { tags -> tags.filter { it[0] != "expiration" } })))
        assertNull(TipIntent.parse(with(mutate = { tags -> tags.map { if (it[0] == "p") listOf("p", "short") else it } })))
    }

    @Test
    fun `parse accepts every tip type`() = runTest {
        val good = signer.sign(intent().toUnsignedEvent())
        for (type in listOf("like", "boost", "tip")) {
            val ev = good.copy(tags = good.tags.map { if (it[0] == "type") listOf("type", type) else it })
            assertEquals(TipType.fromTag(type), TipIntent.parse(ev)?.type, type)
        }
    }

    @Test
    fun `a profile tip has no e tag and round trips`() = runTest {
        val i = TipIntent.create(null, recipient, 1_000, TipType.TIP, "", pid, createdAt = 1700000000)
        val unsigned = i.toUnsignedEvent()
        assertTrue(unsigned.tags.none { it[0] == "e" })
        val parsed = TipIntent.parse(signer.sign(unsigned))
        assertEquals(i, parsed)
        assertNull(parsed!!.noteId)
        assertFalse(parsed.anonymous)
    }

    @Test
    fun `an anonymous intent carries the anon tag last and round trips`() = runTest {
        val i = TipIntent.create(note, recipient, 1_000, TipType.TIP, "", pid, createdAt = 1700000000, anonymous = true)
        val unsigned = i.toUnsignedEvent()
        assertEquals(listOf("anon"), unsigned.tags.last())
        val parsed = TipIntent.parse(signer.sign(unsigned))!!
        assertTrue(parsed.anonymous)
        assertEquals(i, parsed)
        assertFalse(TipIntent.parse(signer.sign(intent().toUnsignedEvent()))!!.anonymous)
    }

    @Test
    fun `a present but malformed e tag is rejected, never read as a profile tip`() = runTest {
        val good = signer.sign(intent().toUnsignedEvent())
        for (bad in listOf(listOf("e", ""), listOf("e", "b".repeat(63)), listOf("e", "B".repeat(64)), listOf("e"))) {
            assertNull(TipIntent.parse(good.copy(tags = good.tags.map { if (it[0] == "e") bad else it })), "$bad")
        }
    }

    @Test
    fun `create accepts no note but rejects a malformed one`() {
        assertNull(TipIntent.create(null, recipient, 1, TipType.TIP, "", pid, 1).noteId)
        assertThrows(IllegalArgumentException::class.java) { TipIntent.create("", recipient, 1, TipType.TIP, "", pid, 1) }
    }
}
