package com.monostr.tips

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Whether a relay (or watcher) URL that came from SOMEONE ELSE's event may be contacted: its host
 * must not be a LAN, loopback or link-local address or a local-only name. Only literals are
 * classified, nothing is resolved: a public name pointing at a LAN address (DNS rebinding) passes.
 * The user's own Settings input does not go through this. A `.onion` host is never public: only the user's own list may name one,
 * and only with Tor on.
 */
object RelayHosts {
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")

    /**
     * Whether [url] names a Tor hidden service: its host (as `java.net.URI` reads it, not a text search) ends in `.onion`, any case,
     * with or without a trailing dot; a bare `onion` is no hidden service. False for anything that does not parse. Nothing is resolved.
     * Java reads fewer URLs than rust-nostr: whatever guards a connection uses [isOnionHost], which does not fail open.
     */
    fun isOnion(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return false
        return host.endsWith(".onion")
    }

    /**
     * [isOnion] for a guard: the host is cut out by hand (after `://`, up to `/ \\ ? #`, without user info and port, percent escapes
     * decoded), so a URL that rust-nostr parses and `java.net.URI` does not (`a_b.onion`, `abc%2Eonion`, a space or backslash in the
     * path) still counts. Meant for the rust-normalised spelling, and for stored strings that rust may normalise later.
     */
    fun isOnionHost(url: String): Boolean {
        val u = url.trim()
        val start = u.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return false
        var end = u.length
        for (c in "/\\?#") u.indexOf(c, start).takeIf { it >= 0 }?.let { end = minOf(end, it) }
        var host = u.substring(start, end).substringAfterLast('@')
        if (!host.startsWith("[")) host = host.substringBefore(':')
        host = PERCENT.replace(host) { it.groupValues[1].toInt(16).toChar().toString() }
        return host.lowercase().trimEnd('.').endsWith(".onion")
    }

    private val PERCENT = Regex("%([0-9a-fA-F]{2})")

    /** [isPublic] without a hidden service: an onion address is no one else's to hand us (spec 2026-11-06 Tor 7.4). */
    fun isPublicWss(url: String): Boolean =
        runCatching { URI(url.trim()) }.getOrNull()?.scheme.equals("wss", ignoreCase = true) && isPublic(url)

    fun isPublic(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return false
        val literal = host.removePrefix("[").removeSuffix("]")
        if (literal.all { it.isDigit() || it == '.' }) return ipv4(literal)?.let { isPublicAddress(it) } ?: false
        // a colon only occurs in an IPv6 literal: parsed, never resolved
        if (':' in literal) return runCatching { InetAddress.getByName(literal) }.getOrNull()?.let { isPublicAddress(it) } ?: false
        if (isLocalName(host) || host.endsWith(".onion")) return false
        return '.' in host
    }

    /** [host] in lower case, without a trailing dot. */
    fun isLocalName(host: String): Boolean = host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }

    /**
     * Exactly four decimal octets without leading zeros, built without a lookup. Any other all-digit
     * form (0177.0.0.1, 127.1, 2130706433) is refused: rust's URL parser reads those as octal or
     * short forms of other addresses than Java would.
     */
    fun ipv4(s: String): InetAddress? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0') || !part.all { it in '0'..'9' }) return null
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
