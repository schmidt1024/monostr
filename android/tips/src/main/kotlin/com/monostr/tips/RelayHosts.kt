package com.monostr.tips

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Whether a relay (or watcher) URL that came from SOMEONE ELSE's event may be contacted: its host
 * must not be a LAN, loopback or link-local address or a local-only name. Only literals are
 * classified, nothing is resolved: a public name pointing at a LAN address (DNS rebinding) passes.
 * The user's own Settings input does not go through this.
 */
object RelayHosts {
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")

    fun isPublicWss(url: String): Boolean =
        runCatching { URI(url.trim()) }.getOrNull()?.scheme.equals("wss", ignoreCase = true) && isPublic(url)

    fun isPublic(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return false
        val literal = host.removePrefix("[").removeSuffix("]")
        if (literal.all { it.isDigit() || it == '.' }) return ipv4(literal)?.let { isPublicAddress(it) } ?: false
        // a colon only occurs in an IPv6 literal: parsed, never resolved
        if (':' in literal) return runCatching { InetAddress.getByName(literal) }.getOrNull()?.let { isPublicAddress(it) } ?: false
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return false
        return '.' in host
    }

    /**
     * Exactly four decimal octets without leading zeros, built without a lookup. Any other all-digit
     * form (0177.0.0.1, 127.1, 2130706433) is refused: rust's URL parser reads those as octal or
     * short forms of other addresses than Java would.
     */
    private fun ipv4(s: String): InetAddress? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0')) return null
            val v = part.toInt()
            if (v > 255) return null
            bytes[i] = v.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    private fun isPublicAddress(addr: InetAddress): Boolean {
        val a = (addr as? Inet6Address)?.let { v4Mapped(it) } ?: addr
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet4Address) {
            val b0 = b[0].toInt() and 0xff
            val b1 = b[1].toInt() and 0xff
            if (b0 == 0) return false
            if (b0 == 100 && b1 in 64..127) return false // CGNAT
        } else if ((b[0].toInt() and 0xfe) == 0xfc) return false // ULA fc00::/7
        return true
    }

    private fun v4Mapped(a: Inet6Address): InetAddress? {
        val b = a.address
        val mapped = (0..9).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
        return if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) else null
    }
}
