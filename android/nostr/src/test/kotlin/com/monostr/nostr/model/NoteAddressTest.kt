package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Coordinate
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Nip19Coordinate
import rust.nostr.sdk.RelayUrl

class NoteAddressTest {
    private val author = Keys.generate().publicKey()
    private fun naddr(kind: Int, d: String, vararg relays: String) =
        Nip19Coordinate(Coordinate(Kind(kind.toUShort()), author, d), relays.map { RelayUrl.parse(it) }).toBech32()

    @Test
    fun `the trigger naddr decodes to the live stream`() {
        val ref = NoteAddress.decode("nostr:naddr1qqjrscnpxqek2wpc94jn2den956rgwtp95unydp395en2cfkvccnxdnpve3k2qg4waehxw309aex2mrp0yhxgctdw4eju6t09upzpn6956apxcad0mfp8grcuugdysg44eepex68h50t73zcathmfs49qvzqqqrkvuvvrnpr")!!
        assertEquals(30311, ref.kind)
        assertEquals("cf45a6ba1363ad7ed213a078e710d24115ae721c9b47bd1ebf4458eaefb4c2a5", ref.pubkey)
        assertEquals("8ba03e88-e573-449a-9241-35a6f136afce", ref.identifier)
        assertEquals(listOf("wss://relay.damus.io"), ref.relays)
        assertEquals("30311:${ref.pubkey}:${ref.identifier}", ref.coordinate)
    }

    @Test
    fun `lan hints are dropped, at most three kept, non addressable kinds refused`() {
        val ref = NoteAddress.decode("nostr:" + naddr(30023, "a", "wss://192.168.1.2", "wss://a.example", "wss://b.example", "wss://c.example", "wss://d.example"))!!
        assertEquals(listOf("wss://a.example", "wss://b.example", "wss://c.example"), ref.relays)
        assertNull(NoteAddress.decode("nostr:" + naddr(1, "x")))
        assertNull(NoteAddress.decode("nostr:naddr1garbage"))
    }

    @Test
    fun `find takes the first decodable naddr, strip removes it only on its own line or at the end`() {
        val n = naddr(30311, "live")
        val text = "WE ARE LIVE\nnostr:$n"
        val ref = NoteAddress.find(text)!!
        assertEquals("live", ref.identifier)
        assertEquals("WE ARE LIVE", NoteAddress.strip(text, ref))
        val mid = "watch nostr:$n now"
        assertEquals(mid, NoteAddress.strip(mid, NoteAddress.find(mid)!!))
    }

    @Test
    fun `toBech32 round-trips`() {
        val ref = AddressRef(30023, author.toHex(), "post", listOf("wss://a.example"))
        assertEquals(ref, NoteAddress.decode("nostr:" + ref.toBech32()))
    }
}
