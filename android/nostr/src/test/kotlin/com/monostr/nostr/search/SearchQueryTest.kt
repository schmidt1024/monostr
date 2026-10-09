package com.monostr.nostr.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Nip19Profile
import rust.nostr.sdk.Nip19Event

class SearchQueryTest {
    private val keys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val pubHex = keys.publicKey().toHex()
    private val npub = keys.publicKey().toBech32()
    private val noteHex = "e".repeat(64)
    private val note1 = EventId.parse(noteHex).toBech32()

    @Test
    fun `npub, nprofile, hex and nostr prefix open a profile`() {
        assertEquals(SearchQuery.Profile(pubHex), SearchQuery.parse(npub))
        assertEquals(SearchQuery.Profile(pubHex), SearchQuery.parse("  nostr:$npub "))
        assertEquals(SearchQuery.Profile(pubHex), SearchQuery.parse(pubHex.uppercase()))
        val nprofile = Nip19Profile(keys.publicKey(), listOf()).toBech32()
        assertEquals(SearchQuery.Profile(pubHex), SearchQuery.parse(nprofile))
    }

    @Test
    fun `uppercase with prefix still opens a profile`() {
        assertEquals(SearchQuery.Profile(pubHex), SearchQuery.parse("NOSTR:${npub.uppercase()}"))
    }

    @Test
    fun `note and nevent open a thread`() {
        assertEquals(SearchQuery.Thread(noteHex), SearchQuery.parse(note1))
        val nevent = Nip19Event(EventId.parse(noteHex), null, null, listOf()).toBech32()
        assertEquals(SearchQuery.Thread(noteHex), SearchQuery.parse("nostr:$nevent"))
    }

    @Test
    fun `hashtags are lowercased and unicode aware`() {
        assertEquals(SearchQuery.Hashtag("monero"), SearchQuery.parse("#Monero"))
        assertEquals(SearchQuery.Hashtag("straße_2"), SearchQuery.parse("#Straße_2"))
        assertEquals(SearchQuery.Text("#two words"), SearchQuery.parse("#two words"))
        assertEquals(SearchQuery.Empty, SearchQuery.parse("#"))
    }

    @Test
    fun `short or blank input is empty, garbage bech32 is text`() {
        assertEquals(SearchQuery.Empty, SearchQuery.parse(""))
        assertEquals(SearchQuery.Empty, SearchQuery.parse(" a "))
        assertEquals(SearchQuery.Text("npub1notvalid"), SearchQuery.parse("npub1notvalid"))
        assertEquals(SearchQuery.Text("alice bob"), SearchQuery.parse("alice bob"))
        assertEquals(SearchQuery.Text("f".repeat(63)), SearchQuery.parse("f".repeat(63)))
    }
}
