package com.monostr.tips

import com.monostr.monero.Address
import com.monostr.monero.AddressException
import com.monostr.monero.Network
import com.monostr.tips.event.UnsignedEvent

/** Kind 10133 (NIP-A3): `["payto", type, value]` tags. Only the Monero entry is read or written here. */
object Payto {
    private const val TAG = "payto"
    private const val TYPE = "monero"

    private fun isMoneroTag(tag: List<String>): Boolean =
        tag.size >= 3 && tag[0] == TAG && (tag[1].equals("monero", ignoreCase = true) || tag[1].equals("xmr", ignoreCase = true))

    /** The first `monero`/`xmr` entry holding a valid mainnet primary or subaddress, trimmed; otherwise `null`. */
    fun moneroAddress(tags: List<List<String>>): String? {
        for (tag in tags) {
            if (!isMoneroTag(tag)) continue
            val value = tag[2].trim()
            val network = try {
                Address.checkReceiving(value)
            } catch (e: AddressException) {
                continue
            }
            if (network == Network.MAINNET) return value
        }
        return null
    }

    /** Drops every Monero payto entry and, if [address] is set, appends exactly one; all other tags stay as they are. */
    fun withMonero(tags: List<List<String>>, address: String?): List<List<String>> {
        val kept = tags.filterNot(::isMoneroTag)
        return if (address == null) kept else kept + listOf(listOf(TAG, TYPE, address))
    }

    fun build(tags: List<List<String>>, createdAt: Long): UnsignedEvent =
        UnsignedEvent(kind = Kinds.PAYTO, content = "", tags = tags, createdAt = createdAt)
}
