package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class AddressLinksTest {
    private val pk = Keys.generate().publicKey().toHex()

    @Test
    fun `live streams open on zap stream, everything else on njump`() {
        assertTrue(AddressLinks.web(AddressRef(30311, pk, "s")).startsWith("https://zap.stream/naddr1"))
        assertTrue(AddressLinks.web(AddressRef(30023, pk, "a")).startsWith("https://njump.me/naddr1"))
    }

    @Test
    fun `the link carries the address and its filtered hints`() {
        val ref = AddressRef(30023, pk, "a", listOf("wss://a.example"))
        val back = NoteAddress.decode("nostr:" + AddressLinks.web(ref).substringAfterLast('/'))!!
        assertEquals(ref, back)
    }
}
