package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ListAddressTest {
    private val pk = "ab".repeat(32)

    @Test
    fun `parse and toString round-trip`() {
        val a = ListAddress.parse("30000:$pk:friends")
        assertEquals(ListAddress(30000, pk, "friends"), a)
        assertEquals("30000:$pk:friends", a.toString())
    }

    @Test
    fun `a d with colons and spaces is kept whole`() {
        val a = ListAddress.parse("30000:$pk:a:b c")
        assertEquals("a:b c", a?.d)
        assertEquals(a, ListAddress.parse(a.toString()))
    }

    @Test
    fun `other kinds, bad keys and short strings are rejected`() {
        assertNull(ListAddress.parse("30001:$pk:x"))
        assertNull(ListAddress.parse("30000:${pk.uppercase()}:x"))
        assertNull(ListAddress.parse("30000:abc:x"))
        assertNull(ListAddress.parse("30000:$pk"))
        assertNull(ListAddress.parse(""))
    }
}
