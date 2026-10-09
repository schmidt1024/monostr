package com.monostr.monero

private const val HEX_CHARS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0f])
    }
    return out.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = hexDigit(this[2 * i])
        val lo = hexDigit(this[2 * i + 1])
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("invalid hex character")
}
