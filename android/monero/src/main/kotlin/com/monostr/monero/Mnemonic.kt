package com.monostr.monero

import java.util.zip.CRC32

class MnemonicException(message: String) : IllegalArgumentException(message)

/** Monero 25-word Electrum-style mnemonic (English word list, prefix length 3). */
object Mnemonic {
    const val WORD_COUNT = 25
    private const val N = 1626L
    private const val PREFIX_LEN = 3

    val words: List<String> by lazy {
        val stream = Mnemonic::class.java.getResourceAsStream("/mnemonic/english.txt")
            ?: error("mnemonic/english.txt resource missing")
        stream.bufferedReader().readLines().filter { it.isNotBlank() }.also {
            check(it.size == N.toInt()) { "word list must have $N words, has ${it.size}" }
        }
    }

    private val index: Map<String, Int> by lazy { words.withIndex().associate { it.value to it.index } }

    fun encode(spendSecretLE: ByteArray): List<String> {
        require(spendSecretLE.size == 32) { "secret must be 32 bytes" }
        val out = ArrayList<String>(WORD_COUNT)
        for (i in 0 until 8) {
            val x = ((spendSecretLE[4 * i].toLong() and 0xff)) or
                ((spendSecretLE[4 * i + 1].toLong() and 0xff) shl 8) or
                ((spendSecretLE[4 * i + 2].toLong() and 0xff) shl 16) or
                ((spendSecretLE[4 * i + 3].toLong() and 0xff) shl 24)
            val w1 = x % N
            val w2 = (x / N + w1) % N
            val w3 = (x / N / N + w2) % N
            out += words[w1.toInt()]
            out += words[w2.toInt()]
            out += words[w3.toInt()]
        }
        out += checksumWord(out)
        return out
    }

    fun checksumWord(first24: List<String>): String {
        require(first24.size == WORD_COUNT - 1) { "checksum needs 24 words" }
        val crc = CRC32()
        for (w in first24) crc.update(w.take(PREFIX_LEN).toByteArray())
        return first24[(crc.value % (WORD_COUNT - 1)).toInt()]
    }

    fun decode(input: List<String>): ByteArray {
        val ws = input.map { it.trim().lowercase() }
        if (ws.size != WORD_COUNT) throw MnemonicException("expected $WORD_COUNT words, got ${ws.size}")
        val idx = ws.mapIndexed { i, w -> index[w] ?: throw MnemonicException("word ${i + 1} is not in the word list") }
        if (checksumWord(ws.dropLast(1)) != ws.last()) throw MnemonicException("checksum word mismatch")

        val out = ByteArray(32)
        for (i in 0 until 8) {
            val w1 = idx[3 * i].toLong()
            val w2 = idx[3 * i + 1].toLong()
            val w3 = idx[3 * i + 2].toLong()
            val x = w1 + N * Math.floorMod(w2 - w1, N) + N * N * Math.floorMod(w3 - w2, N)
            if (x > 0xffffffffL) throw MnemonicException("word group ${i + 1} out of range")
            out[4 * i] = (x and 0xff).toByte()
            out[4 * i + 1] = ((x shr 8) and 0xff).toByte()
            out[4 * i + 2] = ((x shr 16) and 0xff).toByte()
            out[4 * i + 3] = ((x shr 24) and 0xff).toByte()
        }
        return out
    }
}
