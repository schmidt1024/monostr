package com.monostr.app.ui.notifications

import com.monostr.app.ui.common.UiText
import com.monostr.nostr.repo.AboutNote
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind

/** One row ready to draw: the actors' or sender's names and pictures, and the note it is about (null until looked up, or when there is none). */
data class NotificationRowUi(val row: NotificationRow, val names: Map<String, String>, val pictures: Map<String, String?>, val about: AboutNote?)

/** The note a row is about: the group's note, or the single event's [NotificationItem.aboutId]. */
val NotificationRow.aboutId: String?
    get() = when (this) { is NotificationRow.Group -> aboutId; is NotificationRow.Single -> item.aboutId }

/** Whose names and pictures a row shows: the group's actors, or the single event's sender. */
val NotificationRow.pubkeys: List<String>
    get() = when (this) { is NotificationRow.Group -> actors; is NotificationRow.Single -> listOfNotNull(item.from) }

/** What a tap on a notification opens. */
sealed interface NotificationTarget {
    data class Thread(val noteId: String) : NotificationTarget
    data class Profile(val pubkey: String) : NotificationTarget
    /** An anonymous tip to the profile: there is nothing to open. */
    data object None : NotificationTarget
}

/** The note when there is one, else the sender's profile, else nothing. */
val NotificationItem.target: NotificationTarget
    get() {
        val note = noteId
        val sender = from
        return when {
            note != null -> NotificationTarget.Thread(note)
            sender != null -> NotificationTarget.Profile(sender)
            else -> NotificationTarget.None
        }
    }

data class NotificationsUiState(
    val rows: List<NotificationRowUi> = emptyList(),
    /** The visible events behind [rows]; the badge counts these, not rows (spec 11-C §3). */
    val items: List<NotificationItem> = emptyList(),
    val loading: Boolean = true,
    /** A pull-to-refresh is waiting for the relays. */
    val refreshing: Boolean = false,
    /** A background or pulled refresh is asking the relays (spec perf 3.6: the empty state says so). */
    val syncing: Boolean = false,
    /** The session's first refresh is done and no parent note is still looked up (the "notifications complete" mark). */
    val complete: Boolean = false,
    val readAt: Long = 0,
    val message: UiText? = null,
) {
    val unread: Int get() = Unread.count(items, readAt)
}

/** Spec 9.3: replies, mentions and reactions of muted accounts are gone from the list and the badge; money is not noise, their tips stay. */
object NotificationFilter {
    fun visible(items: List<NotificationItem>, muted: Set<String>): List<NotificationItem> =
        if (muted.isEmpty()) items else items.filter { it.kind == NotificationKind.TIP || it.from !in muted }
}

object Unread {
    fun count(items: List<NotificationItem>, readAt: Long, muted: Set<String> = emptySet()): Int =
        NotificationFilter.visible(items, muted).count { it.createdAt > readAt }
}
