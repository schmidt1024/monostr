package com.monostr.monero

import java.math.BigInteger

/**
 * Monero's block-based Base58. Input is split into 8-byte blocks; each block is
 * encoded big-endian into a fixed 11-character string. The trailing partial block
 * (1..7 bytes) uses the fixed sizes in [ENCODED_BLOCK_SIZES].
 */
object MoneroBase58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val FULL_BLOCK_SIZE = 8
    private const val FULL_ENCODED_BLOCK_SIZE = 11
    private val ENCODED_BLOCK_SIZES = intArrayOf(0, 2, 3, 5, 6, 7, 9, 10, 11)
    private val BASE = BigInteger.valueOf(58)
    private val ALPHABET_INDEX = IntArray(128) { -1 }.also { idx ->
        ALPHABET.forEachIndexed { i, c -> idx[c.code] = i }
    }

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size / FULL_BLOCK_SIZE + 1) * FULL_ENCODED_BLOCK_SIZE)
        var offset = 0
        while (offset < data.size) {
            val len = minOf(FULL_BLOCK_SIZE, data.size - offset)
            encodeBlock(data, offset, len, out)
            offset += len
        }
        return out.toString()
    }

    private fun encodeBlock(data: ByteArray, offset: Int, len: Int, out: StringBuilder) {
        var num = BigInteger(1, data.copyOfRange(offset, offset + len))
        val size = ENCODED_BLOCK_SIZES[len]
        val chars = CharArray(size) { ALPHABET[0] }
        var i = size - 1
        while (num.signum() > 0) {
            val qr = num.divideAndRemainder(BASE)
            chars[i--] = ALPHABET[qr[1].toInt()]
            num = qr[0]
        }
        out.append(chars)
    }

    fun decode(s: String): ByteArray {
        val fullBlocks = s.length / FULL_ENCODED_BLOCK_SIZE
        val lastEncodedSize = s.length % FULL_ENCODED_BLOCK_SIZE
        val lastBlockSize = ENCODED_BLOCK_SIZES.indexOf(lastEncodedSize)
        require(lastBlockSize >= 0) { "invalid base58 length" }
        val out = ByteArray(fullBlocks * FULL_BLOCK_SIZE + lastBlockSize)
        var outOffset = 0
        var inOffset = 0
        repeat(fullBlocks) {
            decodeBlock(s, inOffset, FULL_ENCODED_BLOCK_SIZE, out, outOffset, FULL_BLOCK_SIZE)
            inOffset += FULL_ENCODED_BLOCK_SIZE
            outOffset += FULL_BLOCK_SIZE
        }
        if (lastEncodedSize > 0) {
            decodeBlock(s, inOffset, lastEncodedSize, out, outOffset, lastBlockSize)
        }
        return out
    }

    private fun decodeBlock(s: String, inOffset: Int, inLen: Int, out: ByteArray, outOffset: Int, outLen: Int) {
        var num = BigInteger.ZERO
        for (i in inOffset until inOffset + inLen) {
            val c = s[i].code
            val digit = if (c < 128) ALPHABET_INDEX[c] else -1
            require(digit >= 0) { "invalid base58 character '${s[i]}'" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        require(num.bitLength() <= outLen * 8) { "base58 block overflow" }
        val bytes = num.toByteArray() // big-endian, may have leading zero sign byte
        val start = maxOf(0, bytes.size - outLen)
        val copyLen = bytes.size - start
        System.arraycopy(bytes, start, out, outOffset + (outLen - copyLen), copyLen)
    }
}
