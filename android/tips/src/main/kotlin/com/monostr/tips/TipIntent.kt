package com.monostr.tips

import com.monostr.tips.event.Event
import com.monostr.tips.event.UnsignedEvent
import com.monostr.tips.event.isHex64

/** Kind 9738: a sender announces a Monero payment for a note or, without a note, for the recipient's profile. */
data class TipIntent(
    /** The tipped note; null for a tip to the recipient's profile (protocol 0.2). */
    val noteId: String?,
    val recipientPubkey: String,
    val amount: Long,
    val paymentId: PaymentId,
    val type: TipType,
    val comment: String,
    val createdAt: Long,
    val expiration: Long,
    /** The sender does not want to be named: the intent is signed with a one-time key and the receipt carries no `P`. */
    val anonymous: Boolean = false,
) {
    fun isExpired(now: Long): Boolean = now >= expiration

    fun toUnsignedEvent(): UnsignedEvent = UnsignedEvent(
        kind = Kinds.TIP_INTENT,
        content = comment,
        tags = buildList {
            if (noteId != null) add(listOf("e", noteId))
            add(listOf("p", recipientPubkey))
            add(listOf("amount", amount.toString()))
            add(listOf("pid", paymentId.hex))
            add(listOf("type", type.tag))
            add(listOf("expiration", expiration.toString()))
            if (anonymous) add(listOf("anon"))
        },
        createdAt = createdAt,
    )

    companion object {
        /** Lifetime of an intent (spec 3.2 / 5.5): 24 hours. */
        const val TTL_SECONDS = 86_400L

        fun create(
            noteId: String?,
            recipientPubkey: String,
            amount: Long,
            type: TipType,
            comment: String,
            paymentId: PaymentId,
            createdAt: Long,
            anonymous: Boolean = false,
        ): TipIntent {
            require(noteId == null || isHex64(noteId)) { "note id must be 64 lowercase hex chars" }
            require(isHex64(recipientPubkey)) { "recipient pubkey must be 64 lowercase hex chars" }
            require(amount > 0) { "amount must be positive" }
            return TipIntent(noteId, recipientPubkey, amount, paymentId, type, comment, createdAt, createdAt + TTL_SECONDS, anonymous)
        }

        /** Parses a kind 9738 event; null when malformed. A present but malformed `e` is malformed, not a profile tip. */
        fun parse(event: Event): TipIntent? {
            if (event.kind != Kinds.TIP_INTENT) return null
            val noteId = if (event.firstTag("e") != null) (event.firstTagValue("e")?.takeIf(::isHex64) ?: return null) else null
            val recipient = event.firstTagValue("p")?.takeIf(::isHex64) ?: return null
            val amount = event.firstTagValue("amount")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val paymentId = event.firstTagValue("pid")?.let(PaymentId::parse) ?: return null
            val type = event.firstTagValue("type")?.let(TipType::fromTag) ?: return null
            val expiration = event.firstTagValue("expiration")?.toLongOrNull() ?: return null
            return TipIntent(noteId, recipient, amount, paymentId, type, event.content, event.createdAt, expiration, event.firstTag("anon") != null)
        }
    }
}
