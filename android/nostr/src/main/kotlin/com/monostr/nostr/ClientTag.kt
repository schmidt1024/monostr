package com.monostr.nostr

import rust.nostr.sdk.Tag

/**
 * NIP-89 `client` tag: other clients show "via Monostr" under a note that carries it. The address
 * points at Monostr's handler event (kind 31990, `d` = `monostr`) published by the project account
 * (`npub1zcqletx…`); the relay is where that event can be found.
 */
object ClientTag {
    const val NAME = "monostr"
    const val PROJECT_PUBKEY = "1601fcacdf227ddda10e33e71917930b49be436b43bdd030b799948a9f274ed3"
    const val HANDLER_D = "monostr"
    const val RELAY = "wss://relay.monostr.com"
    val value: List<String> = listOf("client", NAME, "31990:$PROJECT_PUBKEY:$HANDLER_D", RELAY)
    fun tag(): Tag = Tag.parse(value)
}
