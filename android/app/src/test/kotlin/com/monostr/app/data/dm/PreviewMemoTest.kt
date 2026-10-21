package com.monostr.app.data.dm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewMemoTest {
    @Test
    fun `a preview is parsed again only when the peer's last message changed`() {
        var parses = 0
        val memo = PreviewMemo { parses++; it.uppercase() }
        assertEquals("HI", memo.of("bob", 100, "hi"))
        memo.of("bob", 100, "hi")
        assertEquals(1, parses)
        assertEquals("YO", memo.of("bob", 200, "yo"))
        assertEquals(2, parses)
        memo.of("carol", 200, "yo")
        assertEquals(3, parses)
        memo.clear()
        memo.of("bob", 200, "yo")
        assertEquals(4, parses)
    }
}
