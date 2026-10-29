package com.monostr.tips

/** Nostr kinds used by the Monostr tip protocol (docs/protocol/monostr-tips.md). */
object Kinds {
    const val PAYMENT_INFO = 10037
    const val WATCHER_STATUS = 10038
    const val TIP_INTENT = 9738
    const val TIP_RECEIPT = 9739
    const val HTTP_AUTH = 27235
    const val DELETION = 5
    /** NIP-22 comment: read like a note where one is the parent of a reply (backlog 26). */
    const val COMMENT = 1111
    /** NIP-A3 payto: payment targets by type, here the Monero address without a watcher. */
    const val PAYTO = 10133
}
