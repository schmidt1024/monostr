package com.monostr.app.ui

import com.monostr.app.ui.common.LinkAction.ASK
import com.monostr.app.ui.common.LinkAction.OPEN
import com.monostr.app.ui.common.LinkPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LinkPolicyTest {
    @Test fun `web links ask, any case`() {
        assertEquals(ASK, LinkPolicy.decide("https://x.example", false, false))
        assertEquals(ASK, LinkPolicy.decide("HTTP://x.example", false, false))
    }

    @Test fun `always open opens, except with Tor`() {
        assertEquals(OPEN, LinkPolicy.decide("https://x.example", true, false))
        assertEquals(ASK, LinkPolicy.decide("https://x.example", true, true))
    }

    @Test fun `wallet handoffs never ask`() {
        assertEquals(OPEN, LinkPolicy.decide("monero:4abc", false, true))
        assertEquals(OPEN, LinkPolicy.decide("lightning:lnbc1", false, true))
    }

    @Test fun `what cannot be read asks`() {
        assertEquals(ASK, LinkPolicy.decide("x.example/no-scheme", false, false))
        assertEquals(ASK, LinkPolicy.decide("", false, false))
    }

    @Test fun `mailto asks`() {
        assertEquals(ASK, LinkPolicy.decide("mailto:a@x.example", true, false))
    }
}
