package com.monostr.app.ui.notifications

import com.monostr.app.ui.common.MentionNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventId

class NotificationQuoteTest {
    private val id = "ab".repeat(32)
    private val uri = EventId.parse(id).toNostrUri()

    @Test
    fun quoteExcerpt_stripsQuoteToken() {
        val q = NotificationExcerpt.quote("my take\n\n$uri")
        assertEquals("my take", q.displayContent)
        assertEquals(id, q.quote?.id)
    }

    @Test
    fun quoteExcerpt_plainTextHasNoQuote() {
        assertNull(NotificationExcerpt.quote("just text").quote)
    }

    @Test
    fun excerpt_noteRefReadsNote() {
        assertEquals("I think note is right", MentionNames.plain("I think $uri is right", emptyMap(), "note"))
    }

    @Test
    fun excerpt_withoutLabelKeepsText() {
        assertEquals("see $uri", MentionNames.plain("see $uri", emptyMap()))
    }
}
