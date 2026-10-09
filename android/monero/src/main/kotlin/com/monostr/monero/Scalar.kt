package com.monostr.monero

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import java.math.BigInteger

/** ed25519 scalar helpers in Monero's little-endian convention. */
object Scalar {
    /** Group order l = 2^252 + 27742317777372353535851937790883648493. */
    val L: BigInteger = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))

    private val basePoint = EdDSANamedCurveTable.getByName("Ed25519").b

    fun toBigInteger(bytesLE: ByteArray): BigInteger {
        require(bytesLE.size == 32) { "scalar must be 32 bytes" }
        return BigInteger(1, bytesLE.reversedArray())
    }

    fun fromBigInteger(n: BigInteger): ByteArray {
        require(n.signum() >= 0 && n.bitLength() <= 256) { "value does not fit in 32 bytes" }
        val be = n.toByteArray()
        val out = ByteArray(32)
        val start = maxOf(0, be.size - 32)
        val len = be.size - start
        System.arraycopy(be, start, out, 32 - len, len)
        return out.reversedArray()
    }

    fun reduce32(bytesLE: ByteArray): ByteArray = fromBigInteger(toBigInteger(bytesLE).mod(L))

    fun isCanonical(bytesLE: ByteArray): Boolean = toBigInteger(bytesLE) < L

    /** Returns the compressed encoding of scalar * G. Scalar must be canonical. */
    fun mulBase(scalarLE: ByteArray): ByteArray {
        require(isCanonical(scalarLE)) { "scalar must be reduced mod l" }
        return basePoint.scalarMultiply(scalarLE).toByteArray()
    }
}
