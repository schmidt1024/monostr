package com.monostr.nostr.model

import kotlinx.serialization.Serializable

/**
 * A BOLT11 Lightning invoice found in a note (spec 2026-11-01 lightning invoice card). Only what the card shows is
 * read: network, amount, timestamp, expiry and description. [raw] is the invoice as written (lowercase, without
 * `lightning:`), what «Open in wallet» hands over. Foreign input: nothing in it is fetched or trusted beyond display.
 */
@Serializable
data class LightningInvoice(
    val raw: String,
    val network: Network,
    /** Millisatoshi; null for an invoice without an amount («any amount»). */
    val amountMsat: Long?,
    val timestamp: Long,
    /** Seconds after [timestamp]; BOLT11's default is one hour. */
    val expiry: Long,
    val description: String?,
    /** Tag `h`: SHA-256 of the long description, hex; NIP-57 binds a zap receipt's request to its invoice with it. */
    val descriptionHash: String? = null,
) {
    enum class Network { MAINNET, TESTNET, SIGNET, REGTEST }

    val amountSat: Long? get() = amountMsat?.let { it / 1000 }

    fun expired(now: Long): Boolean = timestamp + expiry < now
}

/** Finds, strips and decodes BOLT11 invoices; mirrors [NoteAddress] for find and strip. */
object Bolt11 {
    /** `lightning:` optional; `ln` + network + optional amount, the bech32 separator `1`, then the data part. */
    private val REF = Regex("(?<![0-9A-Za-z])(?:(?i:lightning:))?((?i:ln(?:bcrt|tbs|tb|bc))[0-9]*[munpMUNP]?1[02-9ac-hj-np-zAC-HJ-NP-Z]{20,})") // regex
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val SIGNATURE_WORDS = 104
    private const val CHECKSUM_WORDS = 6
    private const val TIMESTAMP_WORDS = 7
    private const val DEFAULT_EXPIRY = 3600L
    /** Tag types, as their bech32 character's value. */
    private const val TAG_DESCRIPTION = 13 // 'd'
    private const val TAG_EXPIRY = 6 // 'x'
    private const val TAG_DESCRIPTION_HASH = 23 // 'h'
    /** 256 bits in 5-bit words. */
    private const val HASH_WORDS = 52
    /** No real invoice is longer; protects the decoder from a megabyte of bech32 characters. */
    private const val MAX_LENGTH = 7089

    /** A cheap check before any regex work: notes without an invoice pay one `contains` (spec performance). */
    fun mayContain(content: String): Boolean = content.contains("lnbc", ignoreCase = true) || content.contains("lntb", ignoreCase = true)

    fun find(content: String): LightningInvoice? = if (!mayContain(content)) null else refs(content).firstOrNull()?.second

    /** Removes the invoice only when it stands on its own line or ends the text, like [NoteQuote.strip]. */
    fun strip(content: String, invoice: LightningInvoice): String {
        val range = refs(content).firstOrNull { it.second.raw == invoice.raw }?.first ?: return content
        val before = content.substring(0, range.first)
        val after = content.substring(range.last + 1)
        val ownLine = before.substringAfterLast('\n').isBlank() && after.substringBefore('\n').isBlank()
        val atEnd = after.isBlank() && (before.isEmpty() || before.last().isWhitespace())
        if (!ownLine && !atEnd) return content
        val head = before.trimEnd()
        val tail = after.trimStart()
        return when {
            head.isEmpty() -> tail
            tail.isEmpty() -> head
            else -> head + "\n" + tail
        }
    }

    /** Every decodable invoice with its range in [content] (including a `lightning:` prefix). */
    fun refs(content: String): List<Pair<IntRange, LightningInvoice>> =
        REF.findAll(content).mapNotNull { m -> decode(m.groupValues[1])?.let { m.range to it } }.toList()

    /** Null for anything that is not a valid BOLT11 invoice: mixed case, bad checksum, unknown network or amount. */
    fun decode(text: String): LightningInvoice? {
        if (text.length > MAX_LENGTH) return null
        if (text != text.lowercase() && text != text.uppercase()) return null
        val s = text.lowercase()
        val sep = s.lastIndexOf('1')
        if (sep < 3 || s.length - sep - 1 < TIMESTAMP_WORDS + SIGNATURE_WORDS + CHECKSUM_WORDS) return null
        val hrp = s.substring(0, sep)
        val words = IntArray(s.length - sep - 1)
        for (i in words.indices) {
            words[i] = CHARSET.indexOf(s[sep + 1 + i]).takeIf { it >= 0 } ?: return null
        }
        if (polymod(expand(hrp) + words) != 1) return null
        val (network, amountText) = network(hrp) ?: return null
        val amountMsat = if (amountText.isEmpty()) null else (amount(amountText) ?: return null)
        val data = words.copyOfRange(0, words.size - CHECKSUM_WORDS - SIGNATURE_WORDS)
        var timestamp = 0L
        for (i in 0 until TIMESTAMP_WORDS) timestamp = (timestamp shl 5) or data[i].toLong()
        var expiry = DEFAULT_EXPIRY
        var description: String? = null
        var descriptionHash: String? = null
        var i = TIMESTAMP_WORDS
        while (i + 3 <= data.size) {
            val type = data[i]
            val len = data[i + 1] * 32 + data[i + 2]
            val start = i + 3
            if (start + len > data.size) return null
            val field = data.copyOfRange(start, start + len)
            when (type) {
                TAG_DESCRIPTION -> if (description == null) description = toBytes(field)?.toString(Charsets.UTF_8)
                TAG_DESCRIPTION_HASH -> if (descriptionHash == null && len == HASH_WORDS) descriptionHash = toBytes(field)?.takeIf { it.size == 32 }?.joinToString("") { "%02x".format(it) }
                TAG_EXPIRY -> if (len in 1..12) expiry = field.fold(0L) { acc, w -> (acc shl 5) or w.toLong() }
            }
            i = start + len
        }
        return LightningInvoice(s, network, amountMsat, timestamp, expiry, description?.trim()?.takeIf { it.isNotEmpty() }, descriptionHash)
    }

    /** `lnbc`, `lntb`, `lntbs`, `lnbcrt` and what follows them (the amount). */
    private fun network(hrp: String): Pair<LightningInvoice.Network, String>? {
        if (!hrp.startsWith("ln")) return null
        val rest = hrp.substring(2)
        val net = listOf(
            "bcrt" to LightningInvoice.Network.REGTEST,
            "tbs" to LightningInvoice.Network.SIGNET,
            "tb" to LightningInvoice.Network.TESTNET,
            "bc" to LightningInvoice.Network.MAINNET,
        ).firstOrNull { rest.startsWith(it.first) } ?: return null
        return net.second to rest.substring(net.first.length)
    }

    /** BOLT11 amount in millisatoshi: digits and an optional multiplier; 1 BTC = 10^11 msat. */
    private fun amount(text: String): Long? {
        val multiplier = text.last().takeIf { it in "munp" }
        val digits = if (multiplier != null) text.dropLast(1) else text
        if (digits.isEmpty() || digits.length > 18 || !digits.all { it.isDigit() } || (digits.length > 1 && digits[0] == '0')) return null
        val n = digits.toLong()
        return try {
            when (multiplier) {
                null -> Math.multiplyExact(n, 100_000_000_000L)
                'm' -> Math.multiplyExact(n, 100_000_000L)
                'u' -> Math.multiplyExact(n, 100_000L)
                'n' -> Math.multiplyExact(n, 100L)
                else -> if (n % 10 == 0L) n / 10 else null // 'p': sub-millisatoshi amounts are invalid
            }
        } catch (e: ArithmeticException) {
            null
        }?.takeIf { it > 0 }
    }

    /** 5-bit words to bytes; trailing bits that do not fill a byte are dropped (BOLT11 field padding). */
    private fun toBytes(words: IntArray): ByteArray? {
        var acc = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (w in words) {
            acc = (acc shl 5) or w
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xff)
            }
            acc = acc and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }

    private fun expand(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1).also { r ->
            hrp.forEachIndexed { i, c -> r[i] = c.code shr 5; r[i + hrp.length + 1] = c.code and 31 }
        }

    private fun polymod(values: IntArray): Int {
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
