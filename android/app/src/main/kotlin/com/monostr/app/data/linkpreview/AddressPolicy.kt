package com.monostr.app.data.linkpreview

import java.net.Inet4Address
import java.net.Inet6Address
import com.monostr.tips.RelayHosts
import java.net.InetAddress

/**
 * Behind a SOCKS proxy the proxy resolves names and no lookup may happen here (Tor spec 4), so only what the host says about
 * itself can be judged: literals by [policy], local-only names and anything that is no public name by their form. Numeric forms
 * other than four plain decimal octets (127.1, 2130706433, 0x7f.0.0.1) are refused, as they mean different things to different
 * resolvers. A public name that points at a LAN address is left to the proxy: Tor's exits do not connect to private addresses.
 * [host] as [okhttp3.HttpUrl.host] gives it. Nothing here resolves.
 */
fun proxiedHostAllowed(host: String, policy: AddressPolicy): Boolean {
    val name = host.lowercase().trimEnd('.')
    if (':' in name) return runCatching { InetAddress.getByName(name) }.getOrNull()?.let(policy::allows) ?: false // a v6 literal: parsed
    val last = name.substringAfterLast('.')
    if (last.all(Char::isDigit) || (last.startsWith("0x") && last.drop(2).all { it.isDigit() || it in 'a'..'f' })) {
        return RelayHosts.ipv4(name)?.let(policy::allows) ?: false
    }
    return '.' in name && !RelayHosts.isLocalName(name)
}

/** Spec 3.3: which addresses a link preview may contact; a note must not make the phone probe the local network. */
fun interface AddressPolicy {
    fun allows(address: InetAddress): Boolean

    companion object {
        val PUBLIC_ONLY = AddressPolicy { isPublic(it) }
        /** Tests only: MockWebServer listens on loopback. */
        val ANY = AddressPolicy { true }

        fun isPublic(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
            val b = a.address
            return when (a) {
                is Inet4Address -> publicV4(b)
                is Inet6Address -> {
                    if ((b[0].toInt() and 0xFE) == 0xFC) return false // unique local fc00::/7
                    // v6 forms that carry a v4 address: the embedded one decides
                    embeddedV4(b)?.let { return publicV4(it) }
                    true
                }
                else -> false
            }
        }

        private fun publicV4(b: ByteArray): Boolean {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            return b0 != 0 && b0 != 10 && b0 != 127 && b0 < 224 && // 0/8, private, loopback, multicast and 240/4 + broadcast
                !(b0 == 100 && b1 in 64..127) && // carrier-grade NAT
                !(b0 == 169 && b1 == 254) && !(b0 == 172 && b1 in 16..31) && !(b0 == 192 && b1 == 168) &&
                !(b0 == 198 && b1 in 18..19) && // benchmarking
                !(b0 == 192 && b1 == 0 && b2 == 0) // IETF protocol assignments
        }

        /** The v4 address inside NAT64 (64:ff9b::/96), 6to4 (2002::/16) or IPv4-compatible (::/96) v6 addresses. */
        private fun embeddedV4(b: ByteArray): ByteArray? = when {
            b[0] == 0x00.toByte() && b[1] == 0x64.toByte() && b[2] == 0xff.toByte() && b[3] == 0x9b.toByte() && (4..11).all { b[it] == 0.toByte() } -> b.copyOfRange(12, 16)
            b[0] == 0x20.toByte() && b[1] == 0x02.toByte() -> b.copyOfRange(2, 6)
            (0..11).all { b[it] == 0.toByte() } -> b.copyOfRange(12, 16)
            else -> null
        }
    }
}
