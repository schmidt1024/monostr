package com.monostr.app.ui

import com.monostr.app.data.RelaySuggestions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Launch feedback: the paid Monero relays link to the page that takes the payment. */
class RelaySignupTest {
    @Test
    fun `a paid Monero relay has its page, any other relay none`() {
        assertEquals("https://nostr.xmr.rocks", RelaySuggestions.signupUrl("wss://nostr.xmr.rocks/"))
        assertEquals("https://xmr.ithurtswhenip.ee", RelaySuggestions.signupUrl(" wss://xmr.ithurtswhenip.ee "))
        assertNull(RelaySuggestions.signupUrl("wss://relay.damus.io"))
        assertNull(RelaySuggestions.signupUrl("wss://relay.monostr.com"))
    }
}
