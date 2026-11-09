package com.monostr.app.ui

import androidx.compose.ui.platform.UriHandler
import com.monostr.app.ui.common.GuardedUriHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardedUriHandlerTest {
    private val opened = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private val delegate = object : UriHandler { override fun openUri(uri: String) { opened += uri } }

    private fun handler(always: Boolean = false, tor: Boolean = false, d: UriHandler = delegate) =
        GuardedUriHandler(d, { always }, { tor }, { asked += it })

    @Test fun webLinkAsksAndDoesNotOpen() {
        handler().openUri("https://x.example")
        assertEquals(listOf("https://x.example"), asked)
        assertTrue(opened.isEmpty())
    }

    @Test fun alwaysOpenGoesToDelegate() {
        handler(always = true).openUri("https://x.example")
        assertEquals(listOf("https://x.example"), opened)
        assertTrue(asked.isEmpty())
    }

    @Test fun walletLinkNeverAsks() {
        handler().openUri("monero:4abc")
        handler().openUri("lightning:lnbc1")
        assertEquals(listOf("monero:4abc", "lightning:lnbc1"), opened)
        assertTrue(asked.isEmpty())
    }

    @Test fun torOnAsksEvenWithAlwaysOpen() {
        handler(always = true, tor = true).openUri("https://x.example")
        assertEquals(1, asked.size)
        assertTrue(opened.isEmpty())
    }

    @Test fun secondLinkWhileAskingOnlyAsksAgainAndNeverOpens() {
        val h = handler()
        h.openUri("https://a.example")
        h.openUri("https://b.example")
        assertEquals(listOf("https://a.example", "https://b.example"), asked)
        assertTrue(opened.isEmpty())
    }

    @Test fun delegateWithoutBrowserDoesNotThrow() {
        val boom = object : UriHandler { override fun openUri(uri: String) = throw IllegalStateException("no browser") }
        handler(always = true, d = boom).openUri("https://x.example")
        handler(d = boom).openUriConfirmed("https://x.example")
    }
}
