package com.monostr.nostr.model

/** NIP-45 counts for one note; null = no relay answered for that kind (spec 4.1). */
data class NoteCounts(val likes: Long?, val reposts: Long?, val replies: Long?) {
    companion object { val EMPTY = NoteCounts(null, null, null) }
}
