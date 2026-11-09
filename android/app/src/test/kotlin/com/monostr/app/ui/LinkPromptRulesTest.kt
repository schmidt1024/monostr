package com.monostr.app.ui

import com.monostr.app.ui.common.LinkPromptRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkPromptRulesTest {
    @Test fun `urls up to the limit are saved, longer ones are not`() {
        assertTrue(LinkPromptRules.persistable("h".repeat(8000)))
        assertFalse(LinkPromptRules.persistable("h".repeat(8001)))
    }

    @Test fun `display keeps up to the limit whole`() {
        val u = "https://x.example/" + "a".repeat(7000)
        assertEquals(u, LinkPromptRules.display(u))
    }

    @Test fun `display cuts beyond the limit with an explicit ellipsis`() {
        val u = "https://x.example/" + "a".repeat(9000)
        val shown = LinkPromptRules.display(u)
        assertEquals(8001, shown.length)
        assertTrue(shown.endsWith("…"))
        assertTrue(shown.startsWith("https://x.example/"))
    }

    @Test fun `only http and https count as web`() {
        assertTrue(LinkPromptRules.isWeb("HTTPS://x.example"))
        assertTrue(LinkPromptRules.isWeb("http://x.example"))
        assertFalse(LinkPromptRules.isWeb("mailto:a@x.example"))
        assertFalse(LinkPromptRules.isWeb("ftp://x.example"))
    }
}
