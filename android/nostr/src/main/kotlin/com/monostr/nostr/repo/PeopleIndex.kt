package com.monostr.nostr.repo

import com.monostr.nostr.model.Profile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Spec perf 3.8: who is in the database, searchable by name, display name and nip05 (case-insensitive). Built once
 * per session from [load] (the stored kind 0, decoded in Kotlin) and kept current with [add] (profiles that land
 * or change later, NIP-50 hits). A failed build is tried again by the next search.
 */
internal class PeopleIndex(private val load: suspend () -> List<Profile>) {
    private class Entry(val profile: Profile, val text: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val mutex = Mutex()
    @Volatile private var built = false

    suspend fun ensureBuilt() {
        if (built) return
        mutex.withLock {
            if (built) return@withLock
            // what [add] put in meanwhile is newer than the database read
            load().forEach { p -> entries.putIfAbsent(p.pubkey, entry(p)) }
            built = true
        }
    }

    fun add(profiles: Collection<Profile>) = profiles.forEach { entries[it.pubkey] = entry(it) }

    suspend fun search(query: String, limit: Int): List<Profile> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        ensureBuilt()
        return entries.values.asSequence().filter { q in it.text }.map { it.profile }.sortedBy { it.shownName.lowercase() }.take(limit).toList()
    }

    private fun entry(p: Profile) = Entry(p, listOfNotNull(p.name, p.displayName, p.nip05).joinToString("\n") { it.lowercase() })
}
