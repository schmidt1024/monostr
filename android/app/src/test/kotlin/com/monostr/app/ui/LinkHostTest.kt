package com.monostr.app.ui

import com.monostr.app.ui.common.LinkHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LinkHostTest {
    @Test fun `userinfo trick shows the real host`() {
        assertEquals("evil.example", LinkHost.of("https://good.example@evil.example/"))
    }

    // WHATWG: a backslash ends the authority like a slash, so a browser contacts good.example (path /@evil.example/)
    @Test fun `backslash is read as a browser reads it`() {
        assertEquals("good.example", LinkHost.of("https://good.example\\@evil.example/"))
    }

    @Test fun `host is lower case`() { assertEquals("example.org", LinkHost.of("https://EXAMPLE.org/x")) }

    @Test fun `ipv6 does not crash`() { assertEquals("::1", LinkHost.of("http://[::1]/")) }

    @Test fun `nothing readable gives null`() {
        assertNull(LinkHost.of("x.example/no-scheme"))
        assertNull(LinkHost.of(""))
        assertNull(LinkHost.of("ftp://x.example"))
    }
}
