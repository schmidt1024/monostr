package com.monostr.app.ui.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Torbrowser brief 2026-11-07: which Tor Browser gets the link, and what is sent to it. */
class TorBrowserTest {
    private val url = "https://example.org/page?q=1"
    private val stable = "org.torproject.torbrowser"
    private val alpha = "org.torproject.torbrowser_alpha"

    @Test
    fun `stable is chosen before alpha when both resolve`() {
        assertEquals(stable, TorBrowser.choose { true })
        assertEquals(listOf(stable, alpha), TorBrowser.PACKAGES)
    }

    @Test
    fun `alpha is chosen when only alpha resolves`() {
        assertEquals(alpha, TorBrowser.choose { it == alpha })
    }

    @Test
    fun `none when neither resolves`() {
        assertNull(TorBrowser.choose { false })
        assertNull(TorBrowser.launch(url) { false })
    }

    @Test
    fun `no other package is ever asked about`() {
        val asked = mutableListOf<String>()
        TorBrowser.choose { asked += it; false }
        assertEquals(listOf(stable, alpha), asked)
    }

    @Test
    fun `the launch is a view of the url bound to the found package`() {
        assertEquals(ViewLaunch("android.intent.action.VIEW", url, stable), TorBrowser.launch(url) { true })
        assertEquals(ViewLaunch("android.intent.action.VIEW", url, alpha), TorBrowser.launch(url) { it == alpha })
    }
}
