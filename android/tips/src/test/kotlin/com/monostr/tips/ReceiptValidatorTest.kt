package com.monostr.tips

import com.monostr.monero.Address
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceiptValidatorTest {
    private val watcherPubkey = "d".repeat(64)
    private val recipient = "c".repeat(64)
    private val address = Address.parse("42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3")
    private val info = PaymentInfo.Enabled(address, "https://watcher.monostr.com", watcherPubkey)

    private fun receipt(watcher: String = watcherPubkey, to: String = recipient, amount: Long = 1000) = TipReceipt(
        id = "1".repeat(64), watcherPubkey = watcher, noteId = "b".repeat(64), recipientPubkey = to,
        senderPubkey = "a".repeat(64), amount = amount, intentId = "e".repeat(64), type = TipType.LIKE, createdAt = 1,
    )

    @Test
    fun `valid when watcher matches and info enabled`() {
        assertTrue(ReceiptValidator.isValid(receipt(), recipient, info))
    }

    @Test
    fun `invalid when signed by another key`() {
        assertFalse(ReceiptValidator.isValid(receipt(watcher = "f".repeat(64)), recipient, info))
    }

    @Test
    fun `invalid when recipient has no payment info`() {
        assertFalse(ReceiptValidator.isValid(receipt(), recipient, PaymentInfo.Disabled))
    }

    @Test
    fun `invalid when p tag does not match the recipient whose info was loaded`() {
        assertFalse(ReceiptValidator.isValid(receipt(to = "9".repeat(64)), recipient, info))
    }

    @Test
    fun `total sums amounts`() {
        assertEquals(0L, ReceiptValidator.total(emptyList()))
        assertEquals(3000L, ReceiptValidator.total(listOf(receipt(amount = 1000), receipt(amount = 2000))))
    }
}
