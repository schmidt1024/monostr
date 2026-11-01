package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-11-01 lightning invoice card. */
class Bolt11Test {
    /** The invoice of the note that started it (Siddh via Amethyst, 2026-11-01): 210u = 21'000 sats, a description hash, no description. */
    private val real = "lnbc210u1p4wwjrypp5kgphh5lvzpptnsxay3rclz803akay574hxz64qv2cpe5qyx66p9qhp54k6ularuecp7avwy2fy0sywvse0gjrxf34ws4tcuyalpukl87pzqcqzysxqrrssrzjqv3dpepm8kfdxrk3sl6wzqdf49s9c0h9ljtjrek6c08r6aejlwcnur0dwyqqvusqqqqqqqlgqqqq86qqjqsp5vxyxnphtncx2scesrfrhmygqz2s4etxsjvzyz8nqwhh0qz75eg8q9qxpqysgqf6uk2y0rqql8re7pyrp9d8cthrlzhdnftx4dahu6jeusvvn0emj88q4280k6f8pmpkpczhvnwx4tm5p6098yuvzxky928ktmyfw7mmgp33s5vq"

    @Test
    fun `the real invoice reads as 21000 sats on mainnet without a description`() {
        val inv = Bolt11.decode(real)!!
        assertEquals(LightningInvoice.Network.MAINNET, inv.network)
        assertEquals(21_000L, inv.amountSat)
        assertEquals(21_000_000L, inv.amountMsat)
        assertNull(inv.description)
        assertTrue(inv.timestamp in 1_790_000_000L..1_800_000_000L, "timestamp ${inv.timestamp}")
    }

    @Test
    fun `uppercase and the lightning prefix are found, mixed case and a broken checksum are not`() {
        assertEquals(21_000L, Bolt11.find("pay me LIGHTNING:${real.uppercase()}")!!.amountSat)
        assertEquals(real, Bolt11.find("lightning:$real")!!.raw)
        assertNull(Bolt11.decode(real.replaceFirst("lnbc", "lnBc")))
        val broken = real.dropLast(1) + (if (real.last() == 'q') 'p' else 'q')
        assertNull(Bolt11.decode(broken))
        assertNull(Bolt11.find("☕ $broken"))
    }

    @Test
    fun `amounts with every multiplier, no amount, and invalid ones`() {
        assertEquals(250_000_000L, Bolt11.decode(invoice("lnbc2500u"))!!.amountMsat) // 250'000 sats
        assertEquals(2_500_000_000L, Bolt11.decode(invoice("lnbc25m"))!!.amountMsat)
        assertEquals(1_000L, Bolt11.decode(invoice("lnbc10n"))!!.amountMsat)
        assertEquals(1L, Bolt11.decode(invoice("lnbc10p"))!!.amountMsat)
        assertEquals(100_000_000_000L, Bolt11.decode(invoice("lnbc1"))!!.amountMsat) // 1 BTC
        assertNull(Bolt11.decode(invoice("lnbc"))!!.amountMsat) // any amount
        assertNull(Bolt11.decode(invoice("lnbc1p"))) // sub-millisatoshi
        assertNull(Bolt11.decode(invoice("lnbc99999999999999999m"))) // overflow
        assertNull(Bolt11.decode(invoice("lnbc010u"))) // leading zero
    }

    @Test
    fun `networks`() {
        assertEquals(LightningInvoice.Network.TESTNET, Bolt11.decode(invoice("lntb20m"))!!.network)
        assertEquals(LightningInvoice.Network.SIGNET, Bolt11.decode(invoice("lntbs20m"))!!.network)
        assertEquals(LightningInvoice.Network.REGTEST, Bolt11.decode(invoice("lnbcrt20m"))!!.network)
        assertNull(Bolt11.decode(invoice("lnxy20m")))
    }

    @Test
    fun `description, expiry and the expired state`() {
        val inv = Bolt11.decode(invoice("lnbc21u", timestamp = 1_000_000, description = "Buy me a coffee ☕", expiry = 600))!!
        assertEquals("Buy me a coffee ☕", inv.description)
        assertEquals(600L, inv.expiry)
        assertFalse(inv.expired(1_000_600))
        assertTrue(inv.expired(1_000_601))
        assertEquals(3600L, Bolt11.decode(invoice("lnbc21u"))!!.expiry) // the default
    }

    @Test
    fun `strip removes an invoice on its own line or at the end, never in the middle of a sentence`() {
        val inv = Bolt11.decode(real)!!
        assertEquals("☕", Bolt11.strip("☕ $real", inv))
        assertEquals("Coffee?\nthanks", Bolt11.strip("Coffee?\n$real\nthanks", inv))
        assertEquals("☕", Bolt11.strip("☕\nlightning:$real", inv))
        val inline = "pay $real if you like it"
        assertEquals(inline, Bolt11.strip(inline, inv))
    }

    @Test
    fun `a note without ln costs one contains and finds nothing`() {
        assertFalse(Bolt11.mayContain("just a note about lightning bolts"))
        assertNull(Bolt11.find("lnurl1dp68gurn8ghj7 is not an invoice"))
    }

    // --- a minimal BOLT11 encoder for the tests: timestamp, optional d and x fields, a zero signature, the checksum ---

    private val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun invoice(hrp: String, timestamp: Long = 1_700_000_000, description: String? = null, expiry: Long? = null): String {
        val data = ArrayList<Int>()
        for (i in 6 downTo 0) data += ((timestamp shr (5 * i)) and 31).toInt()
        if (description != null) field(data, 13, toWords(description.toByteArray(Charsets.UTF_8)))
        if (expiry != null) field(data, 6, numberWords(expiry))
        repeat(104) { data += 0 } // signature: never checked by the decoder
        val checksum = checksum(hrp, data)
        return hrp + "1" + (data + checksum).joinToString("") { charset[it].toString() }
    }

    private fun field(data: MutableList<Int>, type: Int, words: List<Int>) {
        data += type; data += words.size / 32; data += words.size % 32; data += words
    }

    private fun numberWords(n: Long): List<Int> {
        val out = ArrayList<Int>(); var v = n
        do { out.add(0, (v and 31).toInt()); v = v shr 5 } while (v > 0)
        return out
    }

    private fun toWords(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>(); var acc = 0; var bits = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { bits -= 5; out += (acc shr bits) and 31 }
        }
        if (bits > 0) out += (acc shl (5 - bits)) and 31
        return out
    }

    private fun checksum(hrp: String, data: List<Int>): List<Int> {
        val values = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + data + List(6) { 0 }
        val mod = polymod(values) xor 1
        return (0 until 6).map { (mod shr (5 * (5 - it))) and 31 }
    }

    private fun polymod(values: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor gen[i]
        }
        return chk
    }
}
