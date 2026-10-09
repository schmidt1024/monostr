package com.monostr.tips

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
}
