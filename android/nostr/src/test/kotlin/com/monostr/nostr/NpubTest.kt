package com.monostr.nostr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NpubTest {
    @Test
    fun `encodes a hex pubkey as npub and shortens it for display`() {
        val hex = "0d7ceca9e000e711e263bc14a2216ab74968a9e903eba214a714300adede5a20"
        val npub = Npub.encode(hex)
        assertEquals("npub1", npub.take(5))
        assertEquals(63, npub.length)
        assertEquals(npub.take(9) + "…" + npub.takeLast(4), Npub.short(hex))
    }

    @Test
    fun `decodes an npub back to hex and rejects anything else`() {
        val hex = "0d7ceca9e000e711e263bc14a2216ab74968a9e903eba214a714300adede5a20"
        assertEquals(hex, Npub.decodeOrNull(Npub.encode(hex)))
        assertEquals(hex, Npub.decodeOrNull("  " + Npub.encode(hex) + " "))
        org.junit.jupiter.api.Assertions.assertNull(Npub.decodeOrNull(""))
        org.junit.jupiter.api.Assertions.assertNull(Npub.decodeOrNull("npub1garbage"))
        org.junit.jupiter.api.Assertions.assertNull(Npub.decodeOrNull(hex))
    }
}
