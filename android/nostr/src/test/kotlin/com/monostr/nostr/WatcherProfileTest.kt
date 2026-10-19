package com.monostr.nostr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Nip19Profile
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl

class WatcherProfileTest {
    private val hex = Keys.generate().publicKey().toHex()

    private fun nprofile(vararg relays: String): String =
        Nip19Profile(PublicKey.parse(hex), relays.map { RelayUrl.parse(it) }).toBech32()

    @Test
    fun `nprofile gives pubkey and relay hints`() {
        val p = WatcherProfile.parse(nprofile("wss://a.example", "wss://b.example"))
        assertEquals(WatcherProfile(hex, listOf("wss://a.example", "wss://b.example")), p)
    }

    @Test
    fun `npub or hex need the relay field`() {
        val npub = Npub.encode(hex)
        assertEquals(WatcherProfile(hex, listOf("wss://r.example")), WatcherProfile.parse(npub, "wss://r.example"))
        assertEquals(WatcherProfile(hex, emptyList()), WatcherProfile.parse(npub))
        assertEquals(WatcherProfile(hex, emptyList()), WatcherProfile.parse(hex.uppercase()))
        assertEquals(WatcherProfile(hex, listOf("wss://r.example")), WatcherProfile.parse(hex, "wss://r.example"))
    }

    @Test
    fun `lan and loopback relays are dropped`() {
        assertEquals(WatcherProfile(hex, emptyList()), WatcherProfile.parse(hex, "wss://192.168.1.5"))
        assertEquals(listOf("wss://1.example"), WatcherProfile.parse(nprofile("wss://umbrel.local", "wss://127.0.0.1:4848", "wss://1.example"))!!.relays)
    }

    @Test
    fun `relays are filtered to wss and capped at four`() {
        val p = WatcherProfile.parse(
            nprofile("ws://x.example", "wss://1.example", "wss://2.example", "wss://1.example", "wss://3.example", "wss://4.example", "wss://5.example"),
        )
        assertEquals(listOf("wss://1.example", "wss://2.example", "wss://3.example", "wss://4.example"), p!!.relays)
    }

    @Test
    fun `nostr prefix and whitespace are accepted`() {
        assertEquals(WatcherProfile(hex, listOf("wss://a.example")), WatcherProfile.parse("  nostr:" + nprofile("wss://a.example") + "\n"))
        assertEquals(WatcherProfile(hex, emptyList()), WatcherProfile.parse("nostr:" + Npub.encode(hex)))
        assertEquals(WatcherProfile(hex, emptyList()), WatcherProfile.parse("NOSTR:" + Npub.encode(hex)))
        assertEquals(WatcherProfile(hex, listOf("wss://a.example")), WatcherProfile.parse("Nostr:" + nprofile("wss://a.example")))
    }

    @Test
    fun `garbage is null`() {
        assertNull(WatcherProfile.parse("hello"))
        assertNull(WatcherProfile.parse(""))
        assertNull(WatcherProfile.parse(EventId.parse("a".repeat(64)).toBech32()))
    }
}
