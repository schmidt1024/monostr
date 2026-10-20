package com.monostr.app.ui.profile

import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.FollowCounts

/**
 * Spec perf 3.5: what the last [capacity] profiles showed in this session (header, first page per tab, counts); a
 * reopened profile renders it at once and refreshes behind it. One per session ([com.monostr.app.session.Ready]), so it
 * is gone at logout.
 */
class ProfileCache(private val capacity: Int = 20) {
    data class Entry(
        val profile: Profile,
        val posts: List<NoteUi>,
        val replies: List<NoteUi>?,
        val reposts: List<NoteUi>?,
        val counts: FollowCounts?,
    )

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
    }

    /** The lists each profile showed (spec perf 3.7), same capacity and order of use as [entries]. */
    private val listsByAuthor = object : LinkedHashMap<String, List<FollowSet>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FollowSet>>?): Boolean = size > capacity
    }

    @Synchronized fun lists(pubkey: String): List<FollowSet>? = listsByAuthor[pubkey]

    @Synchronized fun putLists(pubkey: String, lists: List<FollowSet>) {
        listsByAuthor[pubkey] = lists
    }

    @Synchronized fun get(pubkey: String): Entry? = entries[pubkey]

    @Synchronized fun put(pubkey: String, entry: Entry) {
        entries[pubkey] = entry
    }

    @Synchronized fun size(): Int = entries.size

    companion object {
        /** Notes kept per tab: the first page. */
        const val PAGE = 50
    }
}
