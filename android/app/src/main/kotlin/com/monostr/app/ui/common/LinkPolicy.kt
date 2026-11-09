package com.monostr.app.ui.common

enum class LinkAction { OPEN, ASK }

/** Opening a web link tells the site the IP, so it asks first; wallet handoffs leave the app and never ask. */
object LinkPolicy {
    private val WALLET_SCHEMES = setOf("monero", "lightning")
    private val WEB_SCHEMES = setOf("http", "https")

    fun decide(uri: String, alwaysOpen: Boolean, torOn: Boolean): LinkAction {
        val scheme = uri.substringBefore(':', "").lowercase()
        if (scheme in WALLET_SCHEMES) return LinkAction.OPEN
        return if (alwaysOpen && !torOn && scheme in WEB_SCHEMES) LinkAction.OPEN else LinkAction.ASK
    }
}
