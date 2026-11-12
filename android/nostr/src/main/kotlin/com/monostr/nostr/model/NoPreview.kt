package com.monostr.nostr.model

/**
 * Owner 2026-11-12: a note whose author put the link card away while writing carries `["preview", "off"]`. No NIP names
 * this: Monostr shows such a note's link as plain text and fetches nothing for it; other clients decide for themselves.
 */
object NoPreview {
    val TAG: List<String> = listOf("preview", "off")

    fun of(tags: List<List<String>>): Boolean = tags.any { it.size >= 2 && it[0] == "preview" && it[1] == "off" }
}
