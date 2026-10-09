package com.monostr.app.ui.notifications

import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind

/** One row of the notifications list (spec 11-C §3): likes and reposts of a note collapse into a [Group]. */
sealed interface NotificationRow {
    val key: String
    val createdAt: Long

    /** Likes or reposts of one note: [actors] distinct, newest first; [count] = all of them. */
    data class Group(val kind: NotificationKind, val aboutId: String, val actors: List<String>, override val createdAt: Long) : NotificationRow {
        override val key: String get() = "${kind.name}:$aboutId"
        val count: Int get() = actors.size
    }

    /** Everything else: one row per event. */
    data class Single(val item: NotificationItem) : NotificationRow {
        override val key: String get() = item.id
        override val createdAt: Long get() = item.createdAt
    }
}

object NotificationGroups {
    /** The kinds that say "your note": grouped per note, and dropped when the note turns out to be somebody else's. */
    val GROUPED = setOf(NotificationKind.REACTION, NotificationKind.REPOST)

    /** Rows newest first; the caller filters muted accounts before this, so a muted liker never counts. */
    fun build(items: List<NotificationItem>): List<NotificationRow> {
        val groups = LinkedHashMap<String, MutableList<NotificationItem>>()
        val singles = ArrayList<NotificationRow>()
        for (item in items) {
            val about = item.aboutId
            val from = item.from
            if (item.kind in GROUPED && about != null && from != null) groups.getOrPut("${item.kind.name}:$about") { ArrayList() } += item
            else singles += NotificationRow.Single(item)
        }
        val grouped = groups.values.map { events ->
            val newestFirst = events.sortedByDescending { it.createdAt }
            NotificationRow.Group(newestFirst.first().kind, newestFirst.first().aboutId!!, newestFirst.map { it.from!! }.distinct(), newestFirst.first().createdAt)
        }
        return (grouped + singles).sortedByDescending { it.createdAt }
    }
}
