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
    /** Protocol 0.5: the `amount` tag is left out because the recipient hides amounts. */
    val hideAmount: Boolean = false,
    /** Tips 0.6: the 32 hex chars [paymentId] is derived from with the signing key; null for an unbound intent. */
    val pidNonce: String? = null,
) {
    fun isExpired(now: Long): Boolean = now >= expiration

    fun toUnsignedEvent(): UnsignedEvent = UnsignedEvent(
        kind = Kinds.TIP_INTENT,
        content = comment,
        tags = buildList {
            if (noteId != null) add(listOf("e", noteId))
            add(listOf("p", recipientPubkey))
            if (!hideAmount) add(listOf("amount", amount.toString()))
            add(listOf("pid", paymentId.hex))
            if (pidNonce != null) add(listOf("pid_nonce", pidNonce))
            add(listOf("type", type.tag))
            add(listOf("expiration", expiration.toString()))
            if (anonymous) add(listOf("anon"))
        },
        createdAt = createdAt,
    )

    companion object {
        private fun isNonceHex(s: String): Boolean = s.length == 2 * PaymentId.NONCE_SIZE && s.all { it in '0'..'9' || it in 'a'..'f' }

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
            hideAmount: Boolean = false,
            pidNonce: String? = null,
        ): TipIntent {
            require(noteId == null || isHex64(noteId)) { "note id must be 64 lowercase hex chars" }
            require(isHex64(recipientPubkey)) { "recipient pubkey must be 64 lowercase hex chars" }
            require(amount > 0) { "amount must be positive" }
            require(pidNonce == null || isNonceHex(pidNonce)) { "pid nonce must be 32 lowercase hex chars" }
            return TipIntent(noteId, recipientPubkey, amount, paymentId, type, comment, createdAt, createdAt + TTL_SECONDS, anonymous, hideAmount, pidNonce)
        }

        /** Parses a kind 9738 event; null when malformed. A present but malformed `e` is malformed, not a profile tip. */
        fun parse(event: Event): TipIntent? {
            if (event.kind != Kinds.TIP_INTENT) return null
            val noteId = if (event.firstTag("e") != null) (event.firstTagValue("e")?.takeIf(::isHex64) ?: return null) else null
            val recipient = event.firstTagValue("p")?.takeIf(::isHex64) ?: return null
            val hasAmount = event.firstTag("amount") != null
            val amount = if (hasAmount) (event.firstTagValue("amount")?.toLongOrNull()?.takeIf { it > 0 } ?: return null) else 0L
            val paymentId = event.firstTagValue("pid")?.let(PaymentId::parse) ?: return null
            val type = if (event.firstTag("type") != null) (event.firstTagValue("type")?.let(TipType::fromTag) ?: return null) else TipType.TIP
            val expiration = event.firstTagValue("expiration")?.toLongOrNull() ?: return null
            // a present but malformed nonce makes the intent invalid, as at the watcher
            val pidNonce = if (event.firstTag("pid_nonce") != null) (event.firstTagValue("pid_nonce")?.takeIf(::isNonceHex) ?: return null) else null
            return TipIntent(noteId, recipient, amount, paymentId, type, event.content, event.createdAt, expiration, event.firstTag("anon") != null, hideAmount = !hasAmount, pidNonce = pidNonce)
        }
    }
}
