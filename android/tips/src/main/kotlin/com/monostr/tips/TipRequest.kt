package com.monostr.tips

import com.monostr.monero.MoneroUri
import com.monostr.tips.event.UnsignedEvent

/** Everything the app needs to send one tip: the event to sign and publish, and the URI to hand to the wallet. */
data class PreparedTip(
    val intent: TipIntent,
    val unsignedEvent: UnsignedEvent,
    val integratedAddress: String,
    val paymentUri: String,
)

/** Sender side of spec 5.5 step 2: payment id → intent → integrated address → `monero:` URI. */
object TipRequest {
    fun prepare(
        recipient: PaymentInfo.Enabled,
        recipientPubkey: String,
        noteId: String?,
        amount: Long,
        type: TipType,
        comment: String,
        now: Long,
        paymentId: PaymentId = PaymentId.random(),
        anonymous: Boolean = false,
    ): PreparedTip {
        val intent = TipIntent.create(noteId, recipientPubkey, amount, type, comment, paymentId, now, anonymous)
        val integrated = recipient.address.integrated(paymentId.bytes)
        val uri = MoneroUri.build(integrated, amount, description(type, noteId ?: recipientPubkey))
        return PreparedTip(intent, intent.toUnsignedEvent(), integrated, uri)
    }

    /** Short wallet-side description; the prefix of the note id (or, for a profile tip, of the recipient's pubkey) helps the sender recognise the payment later. */
    internal fun description(type: TipType, targetId: String): String = "Monostr ${type.tag} ${targetId.take(8)}"
}
