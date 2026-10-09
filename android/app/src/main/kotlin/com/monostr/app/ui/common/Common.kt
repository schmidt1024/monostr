package com.monostr.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.monostr.app.R
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.media.NoteMediaRow
import com.monostr.app.ui.theme.StatusColors
import com.monostr.nostr.model.RelayState
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import java.util.Date
import java.text.DateFormat

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
/** [contentDescription]: what TalkBack reads ("Profile of X"); null for a purely decorative picture. */
fun Avatar(url: String?, modifier: Modifier = Modifier, size: Dp = 40.dp, contentDescription: String? = null) {
    val m = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
        .let { if (contentDescription != null) it.semantics { this.contentDescription = contentDescription } else it }
    if (url.isNullOrBlank()) Box(m) else AsyncImage(model = url, contentDescription = null, modifier = m)
}

/** Relay connectivity dot for the top bar (spec 5.9: no dialogs). */
@Composable
fun RelayDot(states: List<RelayState>) {
    val bestState = when {
        states.any { it == RelayState.CONNECTED } -> RelayState.CONNECTED
        states.any { it == RelayState.CONNECTING } -> RelayState.CONNECTING
        else -> RelayState.DISCONNECTED
    }
    val color = bestState.statusColor()
    val description = stringResource(R.string.relay_status, bestState.label().asString())
    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = description },
    )
}

/** Green / grey / red for a relay state, fixed whatever accent is chosen (light and dark variants). */
@Composable
fun RelayState.statusColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (this) {
        RelayState.CONNECTED -> Color(if (dark) StatusColors.DarkOk else StatusColors.LightOk)
        RelayState.CONNECTING -> MaterialTheme.colorScheme.outline
        RelayState.DISCONNECTED -> Color(if (dark) StatusColors.DarkBad else StatusColors.LightBad)
    }
}

/** Spec 11.5 §3: the focused note's time, written out in the device's locale ("8. Okt. 2026, 14:32"). */
/** Spec 11.5 §3: the detail line under the focused note: the full time, then "· via <client>" when the note names its client (NIP-89); [via] is the localized "via %1$s". */
fun detailTimeLine(time: String, client: String?, via: String): String =
    if (client == null) time else "$time · " + via.replace("%1\$s", client)

fun fullTime(createdAt: Long, locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(createdAt * 1000))

fun relativeTime(createdAt: Long, now: Long = System.currentTimeMillis() / 1000): UiText {
    val d = (now - createdAt).coerceAtLeast(0)
    return when {
        d < 60 -> uiText(R.string.time_now)
        d < 3600 -> uiText(R.string.time_minutes, d / 60)
        d < 86400 -> uiText(R.string.time_hours, d / 3600)
        else -> uiText(R.string.time_days, d / 86400)
    }
}

/** Label for the top-bar dot and the settings relay list. */
fun RelayState.label(): UiText = when (this) {
    RelayState.CONNECTED -> uiText(R.string.relay_connected)
    RelayState.CONNECTING -> uiText(R.string.relay_connecting)
    RelayState.DISCONNECTED -> uiText(R.string.relay_disconnected)
}

/** Reports the keys of the visible items whenever they change (spec 4.2); keys are the note ids. */
@Composable
fun VisibleNotes(listState: LazyListState, onVisible: (List<String>) -> Unit) {
    val callback = rememberUpdatedState(onVisible)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged()
            .collect { if (it.isNotEmpty()) callback.value(it) }
    }
}


/** A note in a list ([detail] false, spec 11.5 §2) or the focused note of a thread ([detail] true, spec 11.5 §3). */
@Composable
fun NoteCard(
    note: NoteUi,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit = {},
    onTip: () -> Unit = {},
    onShowTippers: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onHashtag: (String) -> Unit = {},
    onOpenNote: (String) -> Unit = {},
    onOpenMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    names: Map<String, String> = emptyMap(),
    menu: NoteMenu? = null,
    showActions: Boolean = true,
    collapsed: Boolean = true,
    detail: Boolean = false,
    /** "Replying to @name" on a reply; off in a thread, where the parent stands right above. */
    replyContext: Boolean = true,
) {
    if (detail) {
        DetailNoteCard(note, onOpen, onOpenProfile, onReply, onLike, onRepost, onQuote, onTip, onShowTippers, onBookmark, onHashtag, onOpenNote, onOpenMedia, onOpenVideo, names, menu, showActions)
    } else {
        ListNoteCard(note, onOpen, onOpenProfile, onReply, onLike, onRepost, onQuote, onTip, onShowTippers, onBookmark, onHashtag, onOpenNote, onOpenMedia, onOpenVideo, names, menu, showActions, collapsed, replyContext)
    }
}

/** Spec 11.5 §2: the avatar in its own column, everything else indented beside it, like X's timeline. */
@Composable
private fun ListNoteCard(
    note: NoteUi,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit,
    onTip: () -> Unit,
    onShowTippers: () -> Unit,
    onBookmark: () -> Unit,
    onHashtag: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenMedia: (String, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    names: Map<String, String>,
    menu: NoteMenu?,
    showActions: Boolean,
    collapsed: Boolean,
    replyContext: Boolean,
) {
    val shown = note.note.repostOf ?: note.note
    val shownAuthor = if (note.note.isRepost) note.repostedAuthor else note.author
    val id8 = shown.id.take(8)
    // the 48 dp action targets carry 13 dp of air around their 22 dp glyphs; 4 dp on top of that above the row and
    // a 4 dp card bottom give the row the same 17 dp to the text and to the divider
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = if (showActions) 4.dp else 10.dp)) {
        if (note.note.isRepost) {
            // X puts the repost line above the card, flush with the text column
            Row(Modifier.padding(start = LIST_AVATAR + LIST_GAP), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.note_reposted_by, note.author.shownName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            val openProfile = stringResource(R.string.note_open_profile)
            IconButton(onClick = { onOpenProfile(shown.author) }, modifier = Modifier.size(LIST_AVATAR).semantics { contentDescription = openProfile }.testTag("note-avatar-$id8")) {
                Avatar(shownAuthor.picture, size = LIST_AVATAR)
            }
            Spacer(Modifier.width(LIST_GAP))
            // the free space of the column opens the thread, like the text does — as a plain tap, not a clickable:
            // a clickable would merge the children's semantics (the pending-tip text, TalkBack stops) into one node
            Column(Modifier.weight(1f).pointerInput(onOpen) { detectTapGestures { onOpen() } }) {
                NoteHeaderLine(note, onOpen = onOpen, onOpenProfile = onOpenProfile, menu = menu.takeIf { showActions })
                if (replyContext) ReplyContextLine(shown, names, onOpenProfile)
                if (shown.displayContent.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    NoteText(shown.displayContent, onOpen = onOpen, onHashtag = onHashtag, onOpenProfile = onOpenProfile, onOpenNote = onOpenNote, names = names, collapsible = collapsed, stateKey = shown.id, modifier = Modifier.fillMaxWidth().testTag("note-text-$id8"))
                }
                shown.quotedId?.let { qid ->
                    QuoteCard(qid, shown.quoteRelays, names, onOpenNote = onOpenNote, onOpenProfile = onOpenProfile, onHashtag = onHashtag, modifier = Modifier.padding(top = 8.dp))
                }
                NoteMediaRow(shown, onOpenMedia = { i -> onOpenMedia(shown.id, i) }, onOpenVideo = onOpenVideo)
                if (showActions) {
                    Spacer(Modifier.height(4.dp))
                    NoteActionRow(note, shown, onReply, onRepost, onQuote, onLike, onBookmark, onTip, onShowTippers)
                }
            }
        }
    }
}

/** Spec 11.5 §2: avatar 44 dp, 12 dp to the column (X: 40–48 dp). */
private val LIST_AVATAR = 44.dp
private val LIST_GAP = 12.dp

/** Spec 11.5 §3: the focused note of a thread — avatar, name and handle on top, the text at full width and larger, the written-out time, the actions spread over the width (X's detail view). */
@Composable
private fun DetailNoteCard(
    note: NoteUi,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit,
    onTip: () -> Unit,
    onShowTippers: () -> Unit,
    onBookmark: () -> Unit,
    onHashtag: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenMedia: (String, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    names: Map<String, String>,
    menu: NoteMenu?,
    showActions: Boolean,
) {
    val shown = note.note.repostOf ?: note.note
    val id8 = shown.id.take(8)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        NoteDetailHeader(note, onOpenProfile = onOpenProfile, menu = menu.takeIf { showActions })
        Spacer(Modifier.height(12.dp))
        if (shown.displayContent.isNotBlank()) {
            // the focused note is already open: its text opens nothing (onOpen is a no-op here, like the thread screen passes it)
            NoteText(shown.displayContent, onOpen = onOpen, onHashtag = onHashtag, onOpenProfile = onOpenProfile, onOpenNote = onOpenNote, names = names, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().testTag("note-text-$id8"))
        }
        shown.quotedId?.let { qid ->
            QuoteCard(qid, shown.quoteRelays, names, onOpenNote = onOpenNote, onOpenProfile = onOpenProfile, onHashtag = onHashtag, modifier = Modifier.padding(top = 8.dp))
        }
        NoteMediaRow(shown, onOpenMedia = { i -> onOpenMedia(shown.id, i) }, onOpenVideo = onOpenVideo)
        Spacer(Modifier.height(12.dp))
        Text(detailTimeLine(fullTime(shown.createdAt), shown.client, stringResource(R.string.note_via)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("note-time-$id8"))
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (showActions) NoteActionRow(note, shown, onReply, onRepost, onQuote, onLike, onBookmark, onTip, onShowTippers, spread = true)
    }
}


enum class BottomTab { FEED, SEARCH, BOOKMARKS, NOTIFICATIONS, MESSAGES }

/** Spec 5.7: notifications tab with unread badge; spec 3: search tab after the feed; spec 11.1: five destinations with the bookmarks in the middle, the badged tabs (notifications, messages) on the right. */
@Composable
fun MonostrBottomBar(
    current: BottomTab,
    unread: Int,
    dmUnread: Int,
    onFeed: () -> Unit,
    onSearch: () -> Unit,
    onBookmarks: () -> Unit,
    onMessages: () -> Unit,
    onNotifications: () -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = current == BottomTab.FEED, onClick = onFeed,
            icon = { Icon(if (current == BottomTab.FEED) Icons.Filled.Home else Icons.Outlined.Home, contentDescription = stringResource(R.string.tab_feed), modifier = Modifier.size(TAB_ICON)) }, modifier = Modifier.testTag("tab-feed"),
            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
        )
        NavigationBarItem(
            selected = current == BottomTab.SEARCH, onClick = onSearch,
            icon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.tab_search), modifier = Modifier.size(TAB_ICON)) }, modifier = Modifier.testTag("tab-search"),
            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
        )
        NavigationBarItem(
            selected = current == BottomTab.BOOKMARKS, onClick = onBookmarks,
            icon = { Icon(if (current == BottomTab.BOOKMARKS) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = stringResource(R.string.bookmarks_title), modifier = Modifier.size(TAB_ICON)) }, modifier = Modifier.testTag("tab-bookmarks"),
            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
        )
        NavigationBarItem(
            selected = current == BottomTab.NOTIFICATIONS, onClick = onNotifications,
            icon = {
                BadgedBox(badge = { CountBadge(unread) }) {
                    Icon(if (current == BottomTab.NOTIFICATIONS) Icons.Filled.Notifications else Icons.Outlined.Notifications, contentDescription = stringResource(R.string.tab_notifications), modifier = Modifier.size(TAB_ICON))
                }
            },
            modifier = Modifier.testTag("tab-notifications"),
            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
        )
        NavigationBarItem(
            selected = current == BottomTab.MESSAGES, onClick = onMessages,
            icon = {
                BadgedBox(badge = { CountBadge(dmUnread) }) {
                    Icon(if (current == BottomTab.MESSAGES) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(R.string.tab_messages), modifier = Modifier.size(TAB_ICON))
                }
            },
            modifier = Modifier.testTag("tab-messages"),
            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
        )
    }
}

/** Bottom-bar icons are larger than Material's 24 dp: the bar has no labels (user decision 2026-09-29). */
private val TAB_ICON = 28.dp

@Composable
private fun CountBadge(count: Int) {
    if (count > 0) Badge { Text(if (count > 99) "99+" else count.toString()) }
}
