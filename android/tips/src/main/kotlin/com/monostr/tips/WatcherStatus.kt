package com.monostr.tips

import com.monostr.monero.Address
import com.monostr.monero.AddressException
import com.monostr.monero.Network
import com.monostr.tips.event.Event
import com.monostr.tips.event.isHex64

/** Kind 10038: a personal watcher's heartbeat (spec 0.4). [network] is null when the tag is missing or unknown; [height] 0 = unknown. */
data class WatcherStatus(
    val watcherPubkey: String,
    val recipient: String,
    val address: Address,
    val network: Network?,
    val height: Long,
    val version: String,
    val createdAt: Long,
) {
    fun isOnline(now: Long): Boolean = now - createdAt <= ONLINE_SECONDS

    companion object {
        const val ONLINE_SECONDS = 900L

        fun parse(event: Event): WatcherStatus? {
            if (event.kind != Kinds.WATCHER_STATUS) return null
            val recipient = event.firstTagValue("p")?.takeIf(::isHex64) ?: return null
            val address = event.firstTagValue("address")?.let {
                try { Address.parse(it) } catch (e: AddressException) { null }
            } ?: return null
            val network = event.firstTagValue("network")?.let(NetworkTag::parse)
            val height = event.firstTagValue("height")?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
            return WatcherStatus(event.pubkey, recipient, address, network, height, event.firstTagValue("version").orEmpty(), event.createdAt)
        }
    }
}
