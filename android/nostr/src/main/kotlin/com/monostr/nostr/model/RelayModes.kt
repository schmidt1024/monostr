package com.monostr.nostr.model

import com.monostr.nostr.RelayList

/** What the user uses a relay for. A relay without a stored mode is [BOTH]. */
enum class RelayMode { BOTH, READ, WRITE }

data class RelayEntry(val url: String, val mode: RelayMode)

/**
 * Rules for per-relay read and write modes and their markers in the published kind 10002
 * (spec 2026-11-05 relay read/write). Pure: no storage, no network.
 */
object RelayModes {
    /** [relays] with their [modes] still has at least one relay to read from and one to write to. */
    fun valid(relays: List<String>, modes: Map<String, RelayMode>): Boolean {
        val all = relays.map { modes[it] ?: RelayMode.BOTH }
        return all.any { it != RelayMode.WRITE } && all.any { it != RelayMode.READ }
    }

    /**
     * [modes] made valid for [relays] with as little loss as possible: modes of URLs outside the list go; if still no reader
     * or no writer, the first relay that blocks it (write-only, or read-only) becomes [RelayMode.BOTH]. The rest stays.
     */
    fun repair(relays: List<String>, modes: Map<String, RelayMode>): Map<String, RelayMode> {
        val kept = modes.filterKeys { it in relays }
        if (valid(relays, kept) || relays.isEmpty()) return kept
        val all = relays.map { it to (kept[it] ?: RelayMode.BOTH) }
        val blocking = if (all.none { it.second != RelayMode.WRITE }) RelayMode.WRITE else RelayMode.READ
        val relaxed = all.first { it.second == blocking }.first
        return kept + (relaxed to RelayMode.BOTH)
    }

    /** What the app takes over from a published list; [markersIgnored] when the list could not work and its markers were dropped. */
    data class Adoption(val relays: List<String>, val modes: Map<String, RelayMode>, val markersIgnored: Boolean)

    fun adopt(list: RelayList): Adoption {
        val read = list.read.map(::norm).toSet()
        val write = list.write.map(::norm).toSet()
        val relays = (list.read + list.write).map(::norm).distinct()
        val modes = relays.associateWith { modeOf(it in read, it in write) }
        if (valid(relays, modes)) return Adoption(relays, modes, markersIgnored = false)
        return Adoption(relays, emptyMap(), markersIgnored = relays.isNotEmpty())
    }

    /**
     * The entries to publish for [relays]: the mode the user [chosen] wins, else the marker of the
     * [published] list is kept (so a relay the app never touched keeps its marker), else both.
     */
    fun forPublish(relays: List<String>, chosen: Map<String, RelayMode>, published: RelayList?): List<RelayEntry> {
        val chosenByUrl = chosen.mapKeys { norm(it.key) }
        val read = published?.read.orEmpty().map(::norm).toSet()
        val write = published?.write.orEmpty().map(::norm).toSet()
        return relays.map(::norm).distinct().map { url ->
            val kept = if (url in read || url in write) modeOf(url in read, url in write) else RelayMode.BOTH
            RelayEntry(url, chosenByUrl[url] ?: kept)
        }
    }

    /** The `r` tag: a marker only for a one-way relay. */
    fun tag(entry: RelayEntry): List<String> {
        val url = norm(entry.url)
        return when (entry.mode) {
            RelayMode.BOTH -> listOf("r", url)
            RelayMode.READ -> listOf("r", url, "read")
            RelayMode.WRITE -> listOf("r", url, "write")
        }
    }

    fun modeOf(read: Boolean, write: Boolean) = when {
        read && !write -> RelayMode.READ
        write && !read -> RelayMode.WRITE
        else -> RelayMode.BOTH
    }

    private fun norm(url: String) = url.trim().trimEnd('/')
}
