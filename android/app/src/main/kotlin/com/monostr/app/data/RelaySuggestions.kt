package com.monostr.app.data

/** A relay the settings screen offers with one tap; never part of the defaults. */
data class RelaySuggestion(val url: String, val feeXmr: String)

/** Paid Monero relays from https://pmnr.xmr.rocks/ — reading is free, posting after a one-time XMR payment. */
object RelaySuggestions {
    val PAID_MONERO: List<RelaySuggestion> = listOf(
        RelaySuggestion("wss://xmr.usenostr.org", "0.01"),
        RelaySuggestion("wss://nostr.xmr.rocks", "0.01"),
        RelaySuggestion("wss://nerostr.xmr.rocks", "0.01"),
        RelaySuggestion("wss://xmr.ithurtswhenip.ee", "0.007"),
    )

    /**
     * The page where a paid Monero relay takes the payment that adds an npub to its write list (all four run
     * Nerostr, the page is the relay's host over https); null for any other relay. Launch feedback 2026-10-09.
     */
    fun signupUrl(relayUrl: String): String? {
        val u = relayUrl.trim().trimEnd('/')
        if (PAID_MONERO.none { it.url == u }) return null
        return "https://" + u.removePrefix("wss://")
    }
}
