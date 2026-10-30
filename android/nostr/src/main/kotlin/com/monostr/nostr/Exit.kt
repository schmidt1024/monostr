package com.monostr.nostr

import com.monostr.tips.event.Event as JsonEvent
import rust.nostr.sdk.Event
import java.time.Duration

/**
 * Spec wave 2 B4: when a fetch may stop before every relay answered. Only for the display-only lookups of the spec's
 * call-site table, never on its never-early-exit list (own replaceables, money, routing, anything that reads "every
 * relay answered"). A miss waits like a fetch without [Exit]: every relay's end of stored events, or the timeout.
 */
sealed interface Exit {
    /** Short name for the perf mark. */
    val label: String

    /** Stop once every one of [ids] (hex) was seen. */
    data class AllIds(val ids: Set<String>) : Exit {
        override val label: String get() = "AllIds"
    }

    /** Newest replaceable per author: stop [grace] after every one of [authors] (hex) has an event. */
    data class NewestPerAuthor(val authors: Set<String>, val grace: Duration = Duration.ofMillis(300)) : Exit {
        override val label: String get() = "NewestPerAuthor"
    }

    /** A page (spec 2026-10-30 follow lists): stop [grace] after [count] different events arrived, from whichever relays. */
    data class AtLeast(val count: Int, val grace: Duration = Duration.ofMillis(400)) : Exit {
        override val label: String get() = "AtLeast"
    }

    /** Existence: stop at the first event. */
    data object AnyEvent : Exit {
        override val label: String get() = "AnyEvent"
    }
}

/**
 * What a fetch with [Exit] saw: the events in arrival order (one per id; for [Exit.NewestPerAuthor] an author's older
 * and newer version may both be here), and whether its condition held. Callers that need the newest per author read the
 * store afterwards.
 */
internal class ExitFetch(val events: List<Event>, val met: Boolean)

/** One fetch's bookkeeping for its [Exit]; not thread-safe (one loop, or under that fetch's lock). Ids and pubkeys compare in lower case. */
internal class ExitProgress(private val exit: Exit) {
    private val ids: Set<String> = (exit as? Exit.AllIds)?.ids?.mapTo(HashSet()) { it.lowercase() } ?: emptySet()
    private val authors: Set<String> = (exit as? Exit.NewestPerAuthor)?.authors?.mapTo(HashSet()) { it.lowercase() } ?: emptySet()
    private val seenIds = HashSet<String>()
    private val seenAuthors = HashSet<String>()

    /** Nothing to wait for (no ids, no authors): such a fetch takes the path without [Exit]. */
    val empty: Boolean
        get() = when (exit) {
            is Exit.AllIds -> ids.isEmpty()
            is Exit.NewestPerAuthor -> authors.isEmpty()
            is Exit.AtLeast -> exit.count <= 0
            Exit.AnyEvent -> false
        }

    /** Records [event]; false when its id was seen already (a second relay sent it too). */
    fun add(event: JsonEvent): Boolean {
        if (!seenIds.add(event.id.lowercase())) return false
        seenAuthors += event.pubkey.lowercase()
        return true
    }

    val met: Boolean
        get() = when (exit) {
            is Exit.AllIds -> seenIds.containsAll(ids)
            is Exit.NewestPerAuthor -> seenAuthors.containsAll(authors)
            is Exit.AtLeast -> seenIds.size >= exit.count
            Exit.AnyEvent -> seenIds.isNotEmpty()
        }
}
