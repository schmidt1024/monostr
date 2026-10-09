package com.monostr.monero

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MoneroUriTest {
    private val addr = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"

    @Test
    fun `formatAmount has no float artifacts and trims zeros`() {
        assertEquals("0.005", MoneroUri.formatAmount(5_000_000_000L))
        assertEquals("1", MoneroUri.formatAmount(1_000_000_000_000L))
        assertEquals("1.5", MoneroUri.formatAmount(1_500_000_000_000L))
        assertEquals("0.000000000001", MoneroUri.formatAmount(1L))
        assertEquals("0", MoneroUri.formatAmount(0L))
        assertEquals("123.456789012345", MoneroUri.formatAmount(123_456_789_012_345L))
    }

    @Test
    fun `parseAmount inverts formatAmount and rejects too many decimals`() {
        assertEquals(5_000_000_000L, MoneroUri.parseAmount("0.005"))
        assertEquals(1_000_000_000_000L, MoneroUri.parseAmount("1"))
        assertEquals(1_500_000_000_000L, MoneroUri.parseAmount("1.5"))
        assertEquals(5_000_000_000L, MoneroUri.parseAmount("0,005"))
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.parseAmount("0.0000000000001") }
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.parseAmount("-1") }
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.parseAmount("abc") }
    }

    @Test
    fun `build without description`() {
        assertEquals("monero:$addr?tx_amount=0.005", MoneroUri.build(addr, 5_000_000_000L))
    }

    @Test
    fun `build with description url-encodes`() {
        assertEquals(
            "monero:$addr?tx_amount=0.01&tx_description=Tip%20f%C3%BCr%20note%20%26%20more",
            MoneroUri.build(addr, 10_000_000_000L, "Tip für note & more"),
        )
    }

    @Test
    fun `build rejects non positive amount`() {
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.build(addr, 0L) }
    }

    @Test
    fun `parseAmount rejects amounts that overflow piconero Long`() {
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.parseAmount("99999999999999999999") }
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.parseAmount("9223372.036854775808") }
    }

    @Test
    fun `parseAmount rejects scientific notation and signs`() {
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.parseAmount("1e3") }
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.parseAmount("+1") }
        assertThrows(IllegalArgumentException::class.java) { MoneroUri.parseAmount(".5") }
    }
}
