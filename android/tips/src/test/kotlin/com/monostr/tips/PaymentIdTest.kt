package com.monostr.tips

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class PaymentIdTest {
    @Test
    fun `parse and hex roundtrip`() {
        val pid = PaymentId.parse("0123456789abcdef")!!
        assertEquals("0123456789abcdef", pid.hex)
        assertEquals(8, pid.bytes.size)
        assertEquals(pid, PaymentId(pid.bytes.copyOf()))
        assertEquals(pid.hashCode(), PaymentId(pid.bytes.copyOf()).hashCode())
    }

    @Test
    fun `parse rejects wrong length and uppercase`() {
        assertNull(PaymentId.parse("0123456789abcde"))
        assertNull(PaymentId.parse("0123456789abcdef00"))
        assertNull(PaymentId.parse("0123456789ABCDEF"))
        assertNull(PaymentId.parse(""))
        assertThrows(IllegalArgumentException::class.java) { PaymentId(ByteArray(7)) }
    }

    @Test
    fun `random ids differ`() {
        val rnd = SecureRandom()
        assertNotEquals(PaymentId.random(rnd), PaymentId.random(rnd))
    }

    private val nonce = ByteArray(16) { it.toByte() }

    @Test
    fun `bound pid matches the protocol vectors`() {
        // tips 0.6 test values, shared with the watcher and the spec
        assertEquals("bc14ada49f3e78d6", PaymentId.bound("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", nonce).hex)
        assertEquals("4b827455c05329d0", PaymentId.bound("c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5", nonce).hex)
    }

    @Test
    fun `bound rejects wrong sizes`() {
        assertThrows(IllegalArgumentException::class.java) { PaymentId.bound("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817", nonce) }
        assertThrows(IllegalArgumentException::class.java) { PaymentId.bound("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", nonce.copyOf(15)) }
        assertEquals(PaymentId.NONCE_SIZE, PaymentId.newNonce().size)
    }

    @Test
    fun `tip sender never prints its secret`() {
        val s = TipSender("a".repeat(64), secretHex = "f".repeat(64))
        assertTrue("f".repeat(64) !in s.toString())
    }
}
