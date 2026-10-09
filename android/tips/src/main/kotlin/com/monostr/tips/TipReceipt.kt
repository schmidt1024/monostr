package com.monostr.tips

import com.monostr.tips.event.Event
import com.monostr.tips.event.UnsignedEvent
import com.monostr.tips.event.isHex64

/** Kind 9739: a watcher attests that a payment for an intent arrived. */
data class TipReceipt(
    val id: String,
    val watcherPubkey: String,
    /** The tipped note; null for a tip to the recipient's profile. */
    val noteId: String?,
    val recipientPubkey: String,
    /** Null for an anonymous tip (the intent carried `anon`). */
    val senderPubkey: String?,
    val amount: Long,
    val intentId: String,
    val type: TipType,
    val createdAt: Long,
) {
    companion object {
        fun build(
            noteId: String?,
            recipientPubkey: String,
            senderPubkey: String?,
            amount: Long,
            intentId: String,
            type: TipType,
            createdAt: Long,
        ): UnsignedEvent {
            require((noteId == null || isHex64(noteId)) && isHex64(recipientPubkey) && (senderPubkey == null || isHex64(senderPubkey)) && isHex64(intentId)) { "ids must be 64 lowercase hex chars" }
            require(amount > 0) { "amount must be positive" }
            return UnsignedEvent(
                kind = Kinds.TIP_RECEIPT,
                content = "",
                tags = buildList {
                    if (noteId != null) add(listOf("e", noteId))
                    add(listOf("p", recipientPubkey))
                    if (senderPubkey != null) add(listOf("P", senderPubkey))
                    add(listOf("amount", amount.toString()))
                    add(listOf("intent", intentId))
                    add(listOf("type", type.tag))
                },
                createdAt = createdAt,
            )
        }

        /** Parses a kind 9739 event; null when malformed (a present but malformed `e` or `P` included). Signature validity is the relay layer's job. */
        fun parse(event: Event): TipReceipt? {
            if (event.kind != Kinds.TIP_RECEIPT) return null
            val noteId = if (event.firstTag("e") != null) (event.firstTagValue("e")?.takeIf(::isHex64) ?: return null) else null
            val recipient = event.firstTagValue("p")?.takeIf(::isHex64) ?: return null
            val sender = if (event.firstTag("P") != null) (event.firstTagValue("P")?.takeIf(::isHex64) ?: return null) else null
            val amount = event.firstTagValue("amount")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val intentId = event.firstTagValue("intent")?.takeIf(::isHex64) ?: return null
            val type = event.firstTagValue("type")?.let(TipType::fromTag) ?: return null
            return TipReceipt(event.id, event.pubkey, noteId, recipient, sender, amount, intentId, type, event.createdAt)
        }
    }
}
