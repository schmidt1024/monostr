package com.monostr.nostr.model

import com.monostr.tips.RelayHosts
import kotlinx.serialization.Serializable
import rust.nostr.sdk.Coordinate
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Nip19
import rust.nostr.sdk.Nip19Coordinate
import rust.nostr.sdk.Nip19Enum
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl

/** An addressable event (kinds 30000–39999) a note points at: `kind:pubkey:d` plus the hints of its naddr (filtered, see [NoteAddress]). */
@Serializable
data class AddressRef(val kind: Int, val pubkey: String, val identifier: String, val relays: List<String> = emptyList()) {
    val coordinate: String get() = "$kind:$pubkey:$identifier"

    fun toBech32(): String =
        Nip19Coordinate(Coordinate(Kind(kind.toUShort()), PublicKey.parse(pubkey), identifier), relays.map { RelayUrl.parse(it) }).toBech32()
}

/** `nostr:naddr1…` in note text (spec 2026-10-29 naddr); mirrors [NoteQuote] for find and strip. */
object NoteAddress {
    const val MAX_HINTS = 3
    private val REF = Regex("(?i:nostr:naddr1)[0-9a-zA-Z]+") // regex

    /** Hints come from someone else's note: only public wss relays, at most [MAX_HINTS]. */
    fun decode(link: String): AddressRef? {
        val bech32 = link.substringAfter(':').lowercase()
        val e = runCatching { Nip19.fromBech32(bech32).asEnum() }.getOrNull() as? Nip19Enum.Addr ?: return null
        val c = e.coordinate.coordinate()
        val kind = c.kind().asU16().toInt()
        if (kind !in 30000..39999) return null
        val relays = e.coordinate.relays().map { it.toString().trimEnd('/') }.filter(RelayHosts::isPublicWss).distinct().take(MAX_HINTS)
        return AddressRef(kind, c.publicKey().toHex(), c.identifier(), relays)
    }

    fun find(content: String): AddressRef? = refs(content).firstOrNull()?.second

    /** Removes the reference only when it stands on its own line or ends the text, like [NoteQuote.strip]. */
    fun strip(content: String, ref: AddressRef): String {
        val range = refs(content).firstOrNull { it.second.coordinate == ref.coordinate }?.first ?: return content
        val before = content.substring(0, range.first)
        val after = content.substring(range.last + 1)
        val ownLine = before.substringAfterLast('\n').isBlank() && after.substringBefore('\n').isBlank()
        val atEnd = after.isBlank() && (before.isEmpty() || before.last().isWhitespace())
        if (!ownLine && !atEnd) return content
        val head = before.trimEnd()
        val tail = after.trimStart()
        return when {
            head.isEmpty() -> tail
            tail.isEmpty() -> head
            else -> head + "\n" + tail
        }
    }

    private fun refs(content: String): List<Pair<IntRange, AddressRef>> =
        REF.findAll(content).mapNotNull { m -> decode(m.value)?.let { m.range to it } }.toList()
}
