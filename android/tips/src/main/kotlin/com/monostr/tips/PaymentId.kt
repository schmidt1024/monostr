package com.monostr.tips

import com.monostr.monero.hexToBytes
import com.monostr.monero.toHex
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

        /** Parses 16 lowercase hex chars; null otherwise. */
        fun parse(hex: String): PaymentId? {
            if (hex.length != SIZE * 2 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return PaymentId(hex.hexToBytes())
        }
    }
}
