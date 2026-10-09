package com.monostr.app.ui.notifications

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.monostr.app.ui.common.MentionResolver
import com.monostr.app.ui.common.MentionNames
import com.monostr.app.R
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.repo.AboutNote
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind
import com.monostr.nostr.repo.NotificationsRepository
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /**
     * Badge count that follows the repository's live list and the read marker (spec 5.7) instead of a one-shot `list()`.
     * A like or repost counts only for a note that is the user's own (same rule as the list: a forged tag is no notification).
     */
    fun live(notifications: NotificationsRepository, readAt: Flow<Long>, muted: Flow<Set<String>> = flowOf(emptySet())): Flow<Int> {
        val verified = notifications.live().mapLatest { items ->
            val about = items.filter { it.kind in NotificationGroups.GROUPED }.mapNotNull { it.aboutId }.toSet()
            val own = if (about.isEmpty()) emptySet() else notifications.own(about)
            items.filterNot { it.kind in NotificationGroups.GROUPED && it.aboutId !in own }
        }
        return combine(readAt, verified, muted) { at, items, m -> count(items, at, m) }
    }
}

/** Spec 5.7: live list of replies, mentions, reactions and tips; the read marker lives in settings. */
class NotificationsController(
    private val notifications: NotificationsRepository,
    private val profiles: ProfileRepository,
    private val settings: TipSettingsStore,
    private val scope: CoroutineScope,
    private val muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    private val mentions: MentionResolver = MentionResolver(profiles, scope),
    /** The user's pubkey: a like or repost of a note by anyone else is not "your note" and leaves the list; null (tests) keeps everything. */
    private val me: String? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    fun start() {
        // mentions in the events' texts and in the excerpts of the referenced notes get names
        scope.launch { _state.map { s -> s.rows.flatMap { r -> listOfNotNull((r.row as? NotificationRow.Single)?.item?.text, (r.about as? AboutNote.Found)?.note?.displayContent) } }.distinctUntilChanged().collect { mentions.track(MentionNames.collectTexts(it)) } }
        scope.launch { settings.notificationsReadAt.collect { at -> _state.update { it.copy(readAt = at) } } }
        scope.launch {
            try {
                // the referenced notes known so far: an emission never blanks an excerpt that is already on screen
                var known: Map<String, AboutNote> = emptyMap()
                // collectLatest: a newer list cancels a pending lookup (up to 8 s on a silent relay) instead of waiting for it
                notifications.live().combine(muted) { items, m -> NotificationFilter.visible(items, m) }.collectLatest { items ->
                    val rows = NotificationGroups.build(items)
                    val pubkeys = rows.flatMap { it.pubkeys }.distinct()
                    profiles.prefetch(pubkeys)
                    val profile = pubkeys.associateWith { profiles.get(it) }
                    fun decorate(shown: List<NotificationRow>, about: Map<String, AboutNote>) = shown.map { row ->
                        NotificationRowUi(row, row.pubkeys.associateWith { profile.getValue(it).shownName }, row.pubkeys.associateWith { profile.getValue(it).picture }, row.aboutId?.let { about[it] })
                    }
                    // a like or repost says "your note": with a user set, the row shows only once the note is known to be
                    // the user's own (a forged tag could otherwise put any excerpt under that sentence — security review);
                    // own notes are local, so the lookup is immediate for them
                    fun verified(about: Map<String, AboutNote>, id: String?) = me == null || (about[id] as? AboutNote.Found)?.note?.author == me
                    fun shown(about: Map<String, AboutNote>) = rows.filterNot { it is NotificationRow.Group && !verified(about, it.aboutId) }
                    fun counted(about: Map<String, AboutNote>) = items.filterNot { it.kind in NotificationGroups.GROUPED && !verified(about, it.aboutId) }
                    // the rows first, the referenced notes when the lookup (one relay request at most) is back
                    _state.update { it.copy(rows = decorate(shown(known), known), items = counted(known), loading = false) }
                    val aboutIds = rows.mapNotNull { it.aboutId }.toSet()
                    if (aboutIds.isNotEmpty()) {
                        known = known + notifications.notes(aboutIds)
                        _state.update { it.copy(rows = decorate(shown(known), known), items = counted(known)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = uiText(R.string.notifications_error_load)) }
            }
        }
    }

    fun markRead() {
        scope.launch { runCatching { settings.setNotificationsReadAt(now()) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
