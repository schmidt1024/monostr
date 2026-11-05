package com.monostr.nostr.model

/** NIP-13 proof of work as a reader judges it. */
object Nip13 {
    /**
     * The difficulty a note can claim: the leading zero bits of its id, capped by the target its `nonce` tag commits to.
     * Without a commitment it is 0: NIP-13 lets a client refuse those, and a lucky id is no work done. Most ids stop at
     * the first character, so this adds next to nothing to reading a note.
     */
    fun effectiveBits(id: String, tags: List<List<String>>): Int {
        var bits = 0
        for (c in id) {
            val v = Character.digit(c, 16)
            if (v < 0) return 0
            if (v != 0) {
                bits += Integer.numberOfLeadingZeros(v) - 28
                break
            }
            bits += 4
        }
        if (bits == 0) return 0
        val target = tags.firstOrNull { it.size >= 3 && it[0] == "nonce" }?.get(2)?.toIntOrNull() ?: return 0
        return minOf(bits, target.coerceAtLeast(0))
    }
}
