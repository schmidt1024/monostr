package com.monostr.nostr.model

import com.monostr.tips.event.EventJson
import rust.nostr.sdk.Event
import com.monostr.tips.event.Event as JsonEvent

/**
 * A quoted NIP-57 zap receipt (kind 9735), owner 2026-11-12: what the quote card shows. Read locally from the receipt
 * only; the Lightning payment itself is not (and offline cannot be) verified.
 */
data class ZapQuote(
    val id: String,
    /** From the receipt's `bolt11`, else the zap request's `amount` tag (millisats). */
    val amountSat: Long,
    /** Pubkey of the zap request in `description`; null when it is no validly signed kind 9734. */
    val sender: String?,
    /** The zap request's content, trimmed; empty for a zap without a comment (or without a valid request). */
    val comment: String,
    /** The receipt's `p` tag. */
    val recipient: String,
    /** The receipt's `e` tag: the zapped note, if any. */
    val zappedNote: String?,
    val createdAt: Long,
)

object ZapReceipts {
    const val RECEIPT = 9735
    const val REQUEST = 9734
    private val HEX64 = Regex("[0-9a-f]{64}")

    fun parse(event: Event): ZapQuote? = runCatching { parse(EventJson.decode(event.asJson())) }.getOrNull()

    /**
     * Null for anything that is not a usable receipt: wrong kind, no valid `p`, no amount. A `description` that is no
     * validly signed kind 9734 only drops the sender and the comment (the receipt still shows the amount).
     * [verify]: the zap request's signature check; injectable for tests.
     */
    fun parse(receipt: JsonEvent, verify: (String) -> Boolean = ::verified): ZapQuote? {
        if (receipt.kind != RECEIPT) return null
        val recipient = receipt.firstTagValue("p")?.lowercase()?.takeIf { HEX64.matches(it) } ?: return null
        val requestJson = receipt.firstTagValue("description")
        val signed = requestJson?.let(::request)?.takeIf { verify(requestJson) }
        val bolt11 = receipt.firstTagValue("bolt11")?.trim()?.removePrefix("lightning:")
        val invoice = bolt11?.let(Bolt11::decode)
        val invoiceMsat = invoice?.amountMsat
        val zapped = receipt.firstTagValue("e")?.lowercase()?.takeIf { HEX64.matches(it) }
        // security review: a signed request copied into a foreign receipt must not lend it a sender and a comment. It must name
        // the receipt's recipient and exactly the zapped note the receipt names. Then: an invoice with a description hash binds
        // the request by it (NIP-57); an invoice without one (Fountain writes «PODCAST_ZAP_INVOICE») only when the request
        // states an amount and it is the invoice's. Without an invoice amount nothing binds it: no sender, no comment.
        val request = signed?.takeIf { r ->
            val amount = r.firstTagValue("amount")?.toLongOrNull()
            r.firstTagValue("p")?.lowercase() == recipient &&
                r.firstTagValue("e")?.lowercase() == zapped &&
                invoiceMsat != null &&
                when (val hash = invoice.descriptionHash) {
                    null -> amount == invoiceMsat
                    else -> hash == sha256Hex(requestJson) && (amount == null || amount == invoiceMsat)
                }
        }
        val msat = invoiceMsat
            // display only: an invoice without amount shows what the signed request asked for (it lends no sender)
            ?: signed?.firstTagValue("amount")?.toLongOrNull()?.takeIf { it > 0 }
            ?: return null
        val sat = msat / 1000
        if (sat <= 0) return null
        return ZapQuote(
            id = receipt.id,
            amountSat = sat,
            sender = request?.pubkey,
            comment = request?.content?.trim().orEmpty(),
            recipient = recipient,
            zappedNote = zapped,
            createdAt = receipt.createdAt,
        )
    }

    /** The zap request when [json] is a kind 9734 event with a hex pubkey. */
    private fun request(json: String): JsonEvent? {
        val ev = runCatching { EventJson.decode(json) }.getOrNull() ?: return null
        return ev.takeIf { it.kind == REQUEST && HEX64.matches(it.pubkey) }
    }

    /** NIP-01 id and signature check of the zap request, via rust-nostr. */
    private fun verified(json: String): Boolean = runCatching { Event.fromJson(json).verify() }.getOrDefault(false)

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
