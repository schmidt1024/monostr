package com.monostr.app.ui

import com.monostr.app.ui.notifications.NotificationExcerpt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** v0.13.5: a note "monostr\n\nA Nostr client…" read as "monostr" and an ellipsis in a one- or two-line excerpt. */
class NotificationExcerptTest {
    @Test
    fun `line breaks and runs of blanks become one space, the ends are trimmed`() {
        assertEquals("monostr A Nostr client with Monero tips", NotificationExcerpt.oneLine("monostr\n\nA Nostr client  with\tMonero tips\n"))
    }
}
