package com.monostr.tips

import com.monostr.monero.Network

/** The protocol `network` tag. Only mainnet and stagenet exist in the protocol. */
object NetworkTag {
    fun toTag(network: Network): String = when (network) {
        Network.MAINNET -> "mainnet"
        Network.STAGENET -> "stagenet"
        Network.TESTNET -> throw IllegalArgumentException("testnet is not part of the protocol")
    }

    fun parse(tag: String): Network? = when (tag) {
        "mainnet" -> Network.MAINNET
        "stagenet" -> Network.STAGENET
        else -> null
    }
}
