package com.monostr.monero

enum class Network(val standardPrefix: Int, val integratedPrefix: Int, val subaddressPrefix: Int) {
    MAINNET(18, 19, 42),
    STAGENET(24, 25, 36),
    TESTNET(53, 54, 63);

    companion object {
        fun fromPrefix(prefix: Int): Pair<Network, AddressKind>? {
            for (n in entries) {
                when (prefix) {
                    n.standardPrefix -> return n to AddressKind.STANDARD
                    n.integratedPrefix -> return n to AddressKind.INTEGRATED
                    n.subaddressPrefix -> return n to AddressKind.SUBADDRESS
                }
            }
            return null
        }
    }
}

enum class AddressKind { STANDARD, INTEGRATED, SUBADDRESS }
