package com.monostr.nostr

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class Nip49Test {
    private val vector = "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
    private val vectorHex = "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"

    @Test
    fun specVector() = runBlocking {
        val r = Nip49.decrypt(vector, "nostr")
        assertTrue(r is Nip49.Decrypted.Key)
        assertEquals(vectorHex, (r as Nip49.Decrypted.Key).secretHex)
    }

    @Test
    fun roundTrip() = runBlocking {
        val hex = Keys.generate().secretKey().toHex()
        val enc = Nip49.encrypt(hex, "correct horse ")
        assertTrue(enc.startsWith("ncryptsec1"))
        assertEquals(hex, (Nip49.decrypt(enc, "correct horse ") as Nip49.Decrypted.Key).secretHex)
    }

    @Test
    fun trailingSpaceMatters() = runBlocking {
        val enc = Nip49.encrypt(Keys.generate().secretKey().toHex(), "correct horse ")
        assertEquals(Nip49.Decrypted.WrongPassword, Nip49.decrypt(enc, "correct horse"))
    }

    @Test
    fun wrongPassword() = runBlocking {
        assertEquals(Nip49.Decrypted.WrongPassword, Nip49.decrypt(vector, "nostr2"))
    }

    @Test
    fun garbage() = runBlocking {
        assertEquals(Nip49.Decrypted.Invalid, Nip49.decrypt("ncryptsec1xyz", "x"))
        val nsec = Keys.generate().secretKey().toBech32()
        assertEquals(Nip49.Decrypted.Invalid, Nip49.decrypt(nsec, "x"))
    }

    @Test
    fun detectsTrimmedAnyCase() {
        assertTrue(Nip49.isNcryptsec("  NCRYPTSEC1abc\n"))
        assertFalse(Nip49.isNcryptsec("nsec1abc"))
    }

    @Test
    fun trimsInput() = runBlocking {
        assertTrue(Nip49.decrypt("\n $vector \n", "nostr") is Nip49.Decrypted.Key)
    }

    @Test
    fun keyHidesSecret() {
        assertFalse(Nip49.Decrypted.Key("ab").toString().contains("ab"))
    }

    @Test
    fun readsLogN() {
        assertEquals(16, Nip49.logN(vector))
        assertEquals(null, Nip49.logN("ncryptsec1q"))
    }

    @Test
    fun tooStrongIsRefusedFast() = runBlocking {
        val strong = withLogN(vector, 30)
        assertEquals(30, Nip49.logN(strong))
        val t0 = System.nanoTime()
        assertEquals(Nip49.Decrypted.TooStrong, Nip49.decrypt(strong, "nostr"))
        assertTrue((System.nanoTime() - t0) < 1_000_000_000L)
        assertEquals(Nip49.Decrypted.Key::class, Nip49.decrypt(withLogN(vector, 16), "nostr")::class)
    }

    // test-only bech32 (BIP-173) to re-encode the vector with another log_n and a valid checksum
    private val cs = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun polymod(v: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (x in v) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor x
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor gen[i]
        }
        return chk
    }

    private fun withLogN(s: String, logN: Int): String {
        val hrp = "ncryptsec"
        val data = s.substring(hrp.length + 1, s.length - 6).map { cs.indexOf(it) }.toMutableList()
        var bits = 0L
        for (i in 0 until 4) bits = (bits shl 5) or data[i].toLong() // 20 bits: version, log_n, 4 spare
        bits = (bits and 0xf.toLong()) or (0x02L shl 12) or (logN.toLong() shl 4)
        for (i in 0 until 4) data[i] = ((bits shr (5 * (3 - i))) and 31).toInt()
        val expand = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }
        val mod = polymod(expand + data + List(6) { 0 }) xor 1
        val checksum = (0 until 6).map { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum).joinToString("") { cs[it].toString() }
    }
}
