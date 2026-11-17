package com.monostr.tips

/** Spec 3.4: a receipt counts only if the recipient's own payment info names its signer as watcher. */
object ReceiptValidator {
    fun isValid(receipt: TipReceipt, recipientPubkey: String, recipientInfo: PaymentInfo): Boolean =
        receipt.recipientPubkey == recipientPubkey &&
            recipientInfo is PaymentInfo.Enabled &&
            receipt.watcherPubkey == recipientInfo.watcherPubkey

    /** Saturates at [Long.MAX_VALUE]: a recipient's own watcher may sign any amount, and an overflow showed a negative total. */
    fun total(receipts: Collection<TipReceipt>): Long = receipts.fold(0L) { sum, r ->
        val a = r.amount ?: 0L
        if (a <= 0L) sum else if (sum > Long.MAX_VALUE - a) Long.MAX_VALUE else sum + a
    }
}
