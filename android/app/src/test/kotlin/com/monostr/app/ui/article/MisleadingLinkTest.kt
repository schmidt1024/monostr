package com.monostr.app.ui.article

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MisleadingLinkTest {
    @Test
    fun `a text naming another host than the target is misleading`() {
        assertTrue(MisleadingLink.names("paypal.com", "https://evil.example/login"))
        assertTrue(MisleadingLink.names("Log in at https://bank.example", "https://evil.example"))
        // look-alike letters are compared in punycode
        assertTrue(MisleadingLink.names("p\u0430ypal.com", "https://paypal.com"))
        // hidden characters and look-alike dots do not slip past
        assertTrue(MisleadingLink.names("paypal\u200B.com", "https://evil.example"))
        assertTrue(MisleadingLink.names("paypal\u00ADcom", "https://evil.example"))
        assertTrue(MisleadingLink.names("paypal\u3002com", "https://evil.example"))
        assertTrue(MisleadingLink.names("paypal\uFF0Ecom", "https://evil.example"))
    }

    @Test
    fun `plain words, the same host or its parent domain are not`() {
        assertFalse(MisleadingLink.names("click here", "https://evil.example"))
        assertFalse(MisleadingLink.names("monostr.com", "https://monostr.com/download"))
        assertFalse(MisleadingLink.names("www.monostr.com", "https://monostr.com"))
        assertFalse(MisleadingLink.names("monostr.com", "https://media.monostr.com/x.png"))
    }
}
