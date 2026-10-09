package com.monostr.tips

/** Spec 3.4: a receipt counts only if the recipient's own payment info names its signer as watcher. */
object ReceiptValidator {
    fun isValid(receipt: TipReceipt, recipientPubkey: String, recipientInfo: PaymentInfo): Boolean =
        receipt.recipientPubkey == recipientPubkey &&
            recipientInfo is PaymentInfo.Enabled &&
            receipt.watcherPubkey == recipientInfo.watcherPubkey

    fun total(receipts: Collection<TipReceipt>): Long = receipts.sumOf { it.amount }
}
