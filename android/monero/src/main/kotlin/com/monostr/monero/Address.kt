package com.monostr.monero

/** A Monero primary (standard) address: network byte + spend public + view public. */
class Address(val network: Network, val spendPublic: ByteArray, val viewPublic: ByteArray) {
    init {
        require(spendPublic.size == 32) { "spend public key must be 32 bytes" }
        require(viewPublic.size == 32) { "view public key must be 32 bytes" }
    }

    fun encode(): String = encodeWithPrefix(network.standardPrefix, ByteArray(0))

    /** Integrated address embedding an 8-byte payment id. */
    fun integrated(paymentId: ByteArray): String {
        require(paymentId.size == 8) { "payment id must be 8 bytes" }
        return encodeWithPrefix(network.integratedPrefix, paymentId)
    }

    private fun encodeWithPrefix(prefix: Int, extra: ByteArray): String {
        val body = byteArrayOf(prefix.toByte()) + spendPublic + viewPublic + extra
        val checksum = Keccak.hash256(body).copyOfRange(0, CHECKSUM_SIZE)
        return MoneroBase58.encode(body + checksum)
    }

    override fun equals(other: Any?): Boolean =
        other is Address &&
            network == other.network &&
            spendPublic.contentEquals(other.spendPublic) &&
            viewPublic.contentEquals(other.viewPublic)

    override fun hashCode(): Int =
        31 * (31 * network.hashCode() + spendPublic.contentHashCode()) + viewPublic.contentHashCode()

    override fun toString(): String = encode()

    companion object {
        private const val CHECKSUM_SIZE = 4
        private const val STANDARD_LENGTH = 1 + 32 + 32 + CHECKSUM_SIZE      // 69
        private const val INTEGRATED_LENGTH = STANDARD_LENGTH + 8             // 77

        /** Parses a primary address. Rejects subaddresses and integrated addresses. */
        fun parse(s: String): Address {
            val trimmed = s.trim()
            val bytes = try {
                MoneroBase58.decode(trimmed)
            } catch (e: IllegalArgumentException) {
                throw AddressException.BadFormat(e)
            }
            if (bytes.size != STANDARD_LENGTH && bytes.size != INTEGRATED_LENGTH) throw AddressException.BadLength()

            val body = bytes.copyOfRange(0, bytes.size - CHECKSUM_SIZE)
            val checksum = bytes.copyOfRange(bytes.size - CHECKSUM_SIZE, bytes.size)
            if (!Keccak.hash256(body).copyOfRange(0, CHECKSUM_SIZE).contentEquals(checksum)) {
                throw AddressException.BadChecksum()
            }

            val prefix = body[0].toInt() and 0xff
            val (network, kind) = Network.fromPrefix(prefix) ?: throw AddressException.UnknownPrefix(prefix)
            when (kind) {
                AddressKind.SUBADDRESS -> throw AddressException.SubaddressNotAllowed()
                AddressKind.INTEGRATED -> throw AddressException.IntegratedNotAllowed()
                AddressKind.STANDARD -> if (bytes.size != STANDARD_LENGTH) throw AddressException.BadLength()
            }
            return Address(network, body.copyOfRange(1, 33), body.copyOfRange(33, 65))
        }
    }
}
