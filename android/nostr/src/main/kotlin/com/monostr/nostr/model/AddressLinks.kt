package com.monostr.nostr.model

/** Where an address opens outside the app (spec 2026-10-29 naddr): live streams on zap.stream, everything else on njump.me. */
object AddressLinks {
    fun web(ref: AddressRef): String =
        (if (ref.kind == LiveStream.KIND) "https://zap.stream/" else "https://njump.me/") + ref.toBech32()
}
