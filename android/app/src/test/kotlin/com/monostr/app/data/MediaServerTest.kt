package com.monostr.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MediaServerTest {
    @Test
    fun `an https host is kept, tidied`() {
        assertEquals("https://media.monostr.com", MediaServer.normalize("https://media.monostr.com"))
        assertEquals("https://media.monostr.com", MediaServer.normalize("  https://Media.Monostr.com/  "))
        assertEquals("https://blossom.example:8443", MediaServer.normalize("https://blossom.example:8443/"))
        assertEquals(MediaServer.DEFAULT, MediaServer.normalize(MediaServer.DEFAULT))
    }

    @Test
    fun `the scheme may be in any case, a keyboard capitalises it`() {
        assertEquals("https://blossom.example", MediaServer.normalize("Https://Blossom.Example/"))
        assertEquals("https://blossom.example", MediaServer.normalize("HTTPS://blossom.example"))
        assertNull(MediaServer.normalize("HTTP://blossom.example"))
    }

    @Test
    fun `anything else is refused`() {
        for (bad in listOf(
            "", "media.monostr.com", "http://media.monostr.com", "wss://media.monostr.com", "https://",
            "https://media.monostr.com/upload", "https://media.monostr.com?x=1", "https://media.monostr.com#top",
            "https://user:pw@media.monostr.com", "https://media .monostr.com", "https://a b",
        )) {
            assertNull(MediaServer.normalize(bad), bad)
        }
    }
}
