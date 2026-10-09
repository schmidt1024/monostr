package com.monostr.monero

import org.bouncycastle.jcajce.provider.digest.Keccak as BcKeccak

/** Original Keccak-256 as used by Monero (padding 0x01, not SHA3's 0x06). */
object Keccak {
    fun hash256(data: ByteArray): ByteArray {
        val digest = BcKeccak.Digest256()
        return digest.digest(data)
    }
}
