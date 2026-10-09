package com.monostr.monero

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class MnemonicTest {
    // From monero-python 1.1.1: Seed("00..01").phrase
    private val refPhrase = (
        List(21) { "abbey" } + listOf("bamboo", "jaws", "jerseys", "abbey")
        )
    private val refSecret = "0000000000000000000000000000000000000000000000000000000000000001"

    @Test
    fun `word list has 1626 entries`() {
        assertEquals(1626, Mnemonic.words.size)
        assertEquals("abbey", Mnemonic.words.first())
        assertEquals("zoom", Mnemonic.words.last())
    }

    @Test
    fun `encode reference secret`() {
        assertEquals(refPhrase, Mnemonic.encode(refSecret.hexToBytes()))
    }

    @Test
    fun `decode reference phrase`() {
        assertEquals(refSecret, Mnemonic.decode(refPhrase).toHex())
    }

    @Test
    fun `roundtrip random keys`() {
        val rnd = SecureRandom()
        repeat(50) {
            val k = MoneroKeys.generate(rnd).spendSecret
            assertEquals(k.toHex(), Mnemonic.decode(Mnemonic.encode(k)).toHex())
        }
    }

    @Test
    fun `decode rejects wrong word count, unknown word and bad checksum`() {
        assertThrows(MnemonicException::class.java) { Mnemonic.decode(refPhrase.dropLast(1)) }
        assertThrows(MnemonicException::class.java) { Mnemonic.decode(refPhrase.dropLast(1) + "notaword") }
        val badChecksum = refPhrase.dropLast(1) + "zoom"
        assertThrows(MnemonicException::class.java) { Mnemonic.decode(badChecksum) }
    }

    @Test
    fun `decode is case and whitespace tolerant`() {
        val messy = refPhrase.map { " ${it.uppercase()} " }
        assertEquals(refSecret, Mnemonic.decode(messy).toHex())
    }

    @Test
    fun `unknown word error names the position, not the word`() {
        val e = assertThrows(MnemonicException::class.java) { Mnemonic.decode(refPhrase.dropLast(1) + "notaword") }
        assertEquals("word 25 is not in the word list", e.message)
    }

    @Test
    fun `decode rejects out-of-range word group`() {
        // w1 = n-1, w2 = n-2, w3 = n-3 reconstructs x = 1625 + 1626*1625 + 1626^2*1625 > 0xffffffff
        val n = Mnemonic.words.size
        val group = listOf(Mnemonic.words[n - 1], Mnemonic.words[n - 2], Mnemonic.words[n - 3])
        val first24 = group + List(21) { "abbey" }
        val phrase = first24 + Mnemonic.checksumWord(first24)
        val e = assertThrows(MnemonicException::class.java) { Mnemonic.decode(phrase) }
        assertTrue(e.message!!.contains("out of range"))
    }
}
