package com.monostr.app.ui

import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.RelaySuggestions
import com.monostr.app.data.isValidRelayUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelaySuggestionsTest {
    @Test
    fun `suggestions are valid, unique and never defaults`() {
        val urls = RelaySuggestions.PAID_MONERO.map { it.url }
        assertTrue(urls.isNotEmpty())
        urls.forEach { assertTrue(isValidRelayUrl(it), it) }
        assertEquals(urls.size, urls.toSet().size)
        assertTrue(urls.none { it in PrefsRelayStore.DEFAULT_RELAYS })
        RelaySuggestions.PAID_MONERO.forEach { assertTrue(it.feeXmr.toDouble() > 0.0, it.url) }
    }
}
