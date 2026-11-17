package com.monostr.tips

import com.monostr.monero.hexToBytes
import com.monostr.monero.toHex
import java.security.MessageDigest
import java.security.SecureRandom

/** 8-byte Monero payment id that ties a payment to one tip intent. */
class PaymentId(val bytes: ByteArray) {
    init {
        require(bytes.size == SIZE) { "payment id must be $SIZE bytes" }
    }

    val hex: String get() = bytes.toHex()

    override fun equals(other: Any?): Boolean = other is PaymentId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = hex

    companion object {
        const val SIZE = 8

        fun random(random: SecureRandom = SecureRandom()): PaymentId = PaymentId(ByteArray(SIZE).also(random::nextBytes))

        /** Byte length of the `pid_nonce` of a bound payment id (tips 0.6). */
        const val NONCE_SIZE = 16

        private val PREFIX = "monostr-tips/pid".toByteArray(Charsets.UTF_8)

        /** A fresh nonce for [bound], from a CSPRNG. */
        fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_SIZE).also(random::nextBytes)

        /**
         * Tips 0.6: the payment id of an intent signed by [pubkeyHex], the first 8 bytes of
         * sha256("monostr-tips/pid" || pubkey || [nonce]). A watcher recomputes it, so a pid copied into an intent under
         * another key is invalid.
         */
        fun bound(pubkeyHex: String, nonce: ByteArray): PaymentId {
            require(pubkeyHex.length == 64 && pubkeyHex.all { it in '0'..'9' || it in 'a'..'f' }) { "pubkey must be 64 lowercase hex chars" }
            require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
            val d = MessageDigest.getInstance("SHA-256")
            d.update(PREFIX)
            d.update(pubkeyHex.hexToBytes())
            d.update(nonce)
            return PaymentId(d.digest().copyOf(SIZE))
        }

        /** Parses 16 lowercase hex chars; null otherwise. */
        fun parse(hex: String): PaymentId? {
            if (hex.length != SIZE * 2 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return PaymentId(hex.hexToBytes())
        }
    }
}
