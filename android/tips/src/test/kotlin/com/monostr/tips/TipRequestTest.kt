package com.monostr.tips

import com.monostr.monero.Address
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TipRequestTest {
    private val mainnet = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
    private val integrated = "4CVYY7x1CknTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnbxc5xHroTU8U3vykdq"
    private val info = PaymentInfo.Enabled(Address.parse(mainnet), "https://watcher.monostr.com", "d".repeat(64))
    private val note = "b".repeat(64)
    private val recipient = "c".repeat(64)

    @Test
    fun `prepare builds intent, integrated address and uri from the payment id`() {
        val pid = PaymentId.parse("0123456789abcdef")!!
        val p = TipRequest.prepare(info, recipient, note, 5_000_000_000L, TipType.LIKE, "danke", now = 1700000000, paymentId = pid)

        assertEquals(integrated, p.integratedAddress)
        assertEquals("monero:$integrated?tx_amount=0.005&tx_description=Monostr%20like%20bbbbbbbb", p.paymentUri)
        assertEquals(pid, p.intent.paymentId)
        assertEquals(note, p.intent.noteId)
        assertEquals(recipient, p.intent.recipientPubkey)
        assertEquals(5_000_000_000L, p.intent.amount)
        assertEquals("danke", p.intent.comment)
        assertEquals(1700086400, p.intent.expiration)
        assertEquals(p.intent.toUnsignedEvent(), p.unsignedEvent)
    }

    @Test
    fun `prepare draws a fresh payment id by default`() {
        val a = TipRequest.prepare(info, recipient, note, 1, TipType.BOOST, "", now = 1)
        val b = TipRequest.prepare(info, recipient, note, 1, TipType.BOOST, "", now = 1)
        assertNotEquals(a.intent.paymentId, b.intent.paymentId)
        assertNotEquals(a.integratedAddress, b.integratedAddress)
        assertTrue(a.paymentUri.startsWith("monero:4"))
        assertTrue(a.paymentUri.contains("tx_description=Monostr%20boost%20bbbbbbbb"))
    }

    @Test
    fun `a profile tip has no note, names the recipient in the wallet description and can be anonymous`() {
        val p = TipRequest.prepare(info, recipient, null, 1_000_000_000L, TipType.TIP, "", now = 1700000000, anonymous = true)
        assertNull(p.intent.noteId)
        assertTrue(p.intent.anonymous)
        assertTrue(p.paymentUri.contains("tx_description=Monostr%20tip%20cccccccc"), p.paymentUri)
        assertTrue(p.unsignedEvent.tags.none { it[0] == "e" })
        assertEquals(listOf("anon"), p.unsignedEvent.tags.last())
    }
}
