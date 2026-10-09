package com.monostr.tips.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An event before signing: everything except pubkey, id and sig. */
data class UnsignedEvent(
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
    val createdAt: Long,
)

/** A signed Nostr event as defined by NIP-01. */
@Serializable
data class Event(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** Second element of every tag named [name]. */
    fun tagValues(name: String): List<String> = tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    /** First tag named [name], or null. */
    fun firstTag(name: String): List<String>? = tags.firstOrNull { it.isNotEmpty() && it[0] == name }

    /** Second element of the first tag named [name], or null. */
    fun firstTagValue(name: String): String? = firstTag(name)?.getOrNull(1)
}

/** Signs events. Implemented by the app's key store or a remote signer (NIP-55). */
fun interface EventSigner {
    suspend fun sign(event: UnsignedEvent): Event
}
