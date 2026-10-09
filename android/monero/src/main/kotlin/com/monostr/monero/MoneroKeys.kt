package com.monostr.monero

import java.security.SecureRandom

/**
 * Monero key set derived the standard way: view secret = reduce32(keccak256(spend secret)).
 * All secrets are 32-byte little-endian canonical scalars.
 */
class MoneroKeys(val spendSecret: ByteArray, val viewSecret: ByteArray) {
    init {
        require(Scalar.isCanonical(spendSecret)) { "spend secret must be canonical" }
        require(Scalar.isCanonical(viewSecret)) { "view secret must be canonical" }
    }

    val spendPublic: ByteArray by lazy { Scalar.mulBase(spendSecret) }
    val viewPublic: ByteArray by lazy { Scalar.mulBase(viewSecret) }

    fun address(network: Network): Address = Address(network, spendPublic, viewPublic)

    companion object {
        fun fromSpendSecret(spendSecretLE: ByteArray): MoneroKeys {
            val spend = Scalar.reduce32(spendSecretLE)
            val view = Scalar.reduce32(Keccak.hash256(spend))
            return MoneroKeys(spend, view)
        }

        fun generate(random: SecureRandom = SecureRandom()): MoneroKeys {
            val seed = ByteArray(32)
            random.nextBytes(seed)
            return fromSpendSecret(seed)
        }

        /** True when viewSecret * G equals the address' public view key. */
        fun viewKeyMatches(address: Address, viewSecretLE: ByteArray): Boolean {
            if (viewSecretLE.size != 32 || !Scalar.isCanonical(viewSecretLE)) return false
            return Scalar.mulBase(viewSecretLE).contentEquals(address.viewPublic)
        }
    }
}
