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
}
