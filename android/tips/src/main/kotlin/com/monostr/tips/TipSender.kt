package com.monostr.tips

/**
 * The key that signs a tip intent; the payment id is derived from [pubkey] (tips 0.6). [secretHex] is set only for the
 * one-time key of an anonymous tip, which exists for that one intent and is never stored.
 */
data class TipSender(val pubkey: String, val secretHex: String? = null) {
    override fun toString(): String = "TipSender($pubkey${if (secretHex != null) ", one-time" else ""})"
}
