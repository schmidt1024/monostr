package com.monostr.app.ui

import com.monostr.app.data.PrefsRelayStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultRelaysTest {
    @Test
    fun `own relay is the first default and the public ones remain`() {
        assertEquals("wss://relay.monostr.com", PrefsRelayStore.DEFAULT_RELAYS.first())
        assertTrue(PrefsRelayStore.DEFAULT_RELAYS.containsAll(listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")))
        assertEquals(PrefsRelayStore.DEFAULT_RELAYS.size, PrefsRelayStore.DEFAULT_RELAYS.toSet().size)
    }
}
