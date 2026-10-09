package com.monostr.monero

sealed class AddressException(message: String) : IllegalArgumentException(message) {
    class BadLength : AddressException("address has invalid length")
    class BadFormat(cause: Throwable) : AddressException("address is not valid Base58: ${cause.message}") {
        init { initCause(cause) }
    }
    class BadChecksum : AddressException("address checksum mismatch")
    class UnknownPrefix(prefix: Int) : AddressException("unknown address prefix $prefix")
    class SubaddressNotAllowed : AddressException("subaddresses are not supported; use the primary address (4… / 5…)")
    class IntegratedNotAllowed : AddressException("integrated addresses are not supported; use the primary address (4… / 5…)")
}
