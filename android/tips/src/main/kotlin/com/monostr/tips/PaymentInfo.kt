package com.monostr.tips

import com.monostr.monero.Address
import com.monostr.monero.AddressException
import com.monostr.monero.Network
import com.monostr.tips.event.Event
import com.monostr.tips.event.UnsignedEvent
import com.monostr.tips.event.isHex64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Kind 10037: where and through which watcher a user accepts Monero tips. */
sealed class PaymentInfo {
    /** No usable payment info: the user takes no tips (spec 5.9). */
    data object Disabled : PaymentInfo()

    data class Enabled(
        val address: Address,
        val watcherUrl: String,
        val watcherPubkey: String,
    ) : PaymentInfo() {
        val network: Network get() = address.network
    }

    companion object {
        fun build(address: Address, watcherUrl: String, watcherPubkey: String, createdAt: Long): UnsignedEvent {
            require(isHex64(watcherPubkey)) { "watcher pubkey must be 64 lowercase hex chars" }
            val url = normalizeUrl(watcherUrl) ?: throw IllegalArgumentException("watcher url must start with http:// or https://")
            return UnsignedEvent(
                kind = Kinds.PAYMENT_INFO,
                content = "",
                tags = listOf(
                    listOf("address", address.encode()),
                    listOf("watcher", url, watcherPubkey),
                    listOf("network", NetworkTag.toTag(address.network)),
                ),
                createdAt = createdAt,
            )
        }

        fun buildDisabled(createdAt: Long): UnsignedEvent =
            UnsignedEvent(kind = Kinds.PAYMENT_INFO, content = "", tags = emptyList(), createdAt = createdAt)

        /** Parses a kind 10037 event. Anything malformed yields [Disabled]. */
        fun parse(event: Event): PaymentInfo {
            if (event.kind != Kinds.PAYMENT_INFO) return Disabled
            val addressText = event.firstTagValue("address") ?: return Disabled
            val watcher = event.firstTag("watcher") ?: return Disabled
            if (watcher.size < 3) return Disabled
            val url = normalizeUrl(watcher[1]) ?: return Disabled
            val pubkey = watcher[2]
            if (!isHex64(pubkey)) return Disabled
            val network = event.firstTagValue("network")?.let(NetworkTag::parse) ?: return Disabled
            val address = try {
                Address.parse(addressText)
            } catch (e: AddressException) {
                return Disabled
            }
            if (address.network != network) return Disabled
            return Enabled(address, url, pubkey)
        }

        /**
         * Parses and canonicalizes a watcher URL, or returns `null` if it is not a plain
         * `http(s)` origin + path (no userinfo, query or fragment). The watcher URL in a
         * kind 10037 event is attacker-controlled (spec 5.9: bad watcher must fail softly).
         */
        private fun normalizeUrl(url: String): String? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "http" && parsed.scheme != "https") return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            if (parsed.query != null || parsed.fragment != null) return null
            if (parsed.host.isEmpty()) return null
            return parsed.toString().trimEnd('/')
        }
    }
}
