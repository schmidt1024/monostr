package com.monostr.app.ui.article

import com.monostr.nostr.model.AddressRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmbedTargetTest {
    private val ref = AddressRef(30023, "a".repeat(64), "d", listOf("wss://attacker.example"))

    @Test
    fun `a reader opened from a direct message drops the hints and stays on own relays`() {
        assertEquals(ref.copy(relays = emptyList()) to false, embedTarget(ref, false))
    }

    @Test
    fun `a normal reader leaves embeds unchanged`() {
        assertEquals(ref to true, embedTarget(ref, true))
    }
}
