package com.monostr.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.ui.common.HideOnScrollState
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.common.NoteMenu
import com.monostr.app.ui.common.PendingRow
import com.monostr.app.ui.common.ShowBarsWhenStuck
import com.monostr.app.ui.common.ShowRepliesRow
import com.monostr.app.ui.common.UnavailableRow
import com.monostr.app.ui.common.VisibleNotes
import com.monostr.nostr.model.Note

/** Where taps in a note list lead: the same on the home feed, its list tabs and the list screen. */
class NoteNav(
    val onOpenThread: (String) -> Unit,
    val onOpenProfile: (String) -> Unit,
    val onReply: (String) -> Unit,
    val onQuote: (String) -> Unit,
    val onHashtag: (String) -> Unit,
    val onOpenMedia: (String, Int) -> Unit,
    val onOpenVideo: (String) -> Unit,
    val onTip: (Note) -> Unit,
    val onShowTippers: (Note) -> Unit,
)

/**
 * One feed's notes with pull-to-refresh, conversation blocks (spec 2026-10-16 §3), paging and the feed's
 * snackbar messages; the home feed's tabs and the list screen each pass their own [feed] and [listState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedList(
    feed: FeedController,
    listState: LazyListState,
    menu: NoteMenu,
    nav: NoteNav,
    emptyText: String,
    snackbar: SnackbarHostState,
    hide: HideOnScrollState,
    header: LazyListScope.() -> Unit = {},
) {
    val state by feed.state.collectAsStateWithLifecycle()
    val names by feed.names.collectAsStateWithLifecycle()
    MessageSnackbar(state.message, snackbar, onUnmute = feed::unmute, onShown = feed::clearMessage)
    ShowBarsWhenStuck(hide, listState, hasList = !state.loading && state.entries.isNotEmpty())
    // the first load already shows the large spinner; the refresh it starts must not add the small one on top
    PullToRefreshBox(isRefreshing = state.refreshing && !state.loading, onRefresh = feed::refresh, modifier = Modifier.fillMaxSize().testTag("feed-refresh")) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.entries.isEmpty()) {
            // scrollable so pull-to-refresh works on the empty state too
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) { Text(emptyText, textAlign = TextAlign.Center) }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("feed-list")) {
                header()
                items(state.entries, key = { it.note.id }) { n ->
                    // spec 2026-10-16 §3: a reply stands under the notes above it, joined by the thread line (X's conversation block)
                    val chain = state.chains[n.note.id]
                    Column(Modifier.testTag(if (chain != null) "conversation-${n.note.id.take(8)}" else "entry-${n.note.id.take(8)}")) {
                        chain?.ancestors?.forEachIndexed { i, id ->
                            val above = i > 0
                            val a = state.ancestors[id]
                            if (a == null && id in state.pending) PendingRow(lineAbove = above, lineBelow = true)
                            else if (a == null) UnavailableRow(lineAbove = above, lineBelow = true)
                            else NoteCard(
                                note = a,
                                onOpen = { nav.onOpenThread(a.target.id) },
                                onOpenProfile = nav.onOpenProfile,
                                onReply = { nav.onReply(a.target.id) },
                                onLike = { feed.like(a) },
                                onReact = { e -> feed.react(a.target, e) },
                                onRepost = { feed.repost(a) },
                                onQuote = { nav.onQuote(a.target.id) },
                                onTip = { nav.onTip(a.target) },
                                onShowTippers = { nav.onShowTippers(a.target) },
                                onBookmark = { feed.bookmark(a) },
                                onHashtag = nav.onHashtag,
                                onOpenNote = nav.onOpenThread,
                                onOpenMedia = nav.onOpenMedia,
                                onOpenVideo = nav.onOpenVideo,
                                names = names,
                                menu = menu,
                                replyContext = false,
                                lineAbove = above,
                                lineBelow = true,
                            )
                            if (i == 0 && chain.cut) ShowRepliesRow(onClick = { nav.onOpenThread(n.target.id) })
                        }
                        NoteCard(
                            note = n,
                            onOpen = { nav.onOpenThread(n.target.id) },
                            onOpenProfile = nav.onOpenProfile,
                            onReply = { nav.onReply(n.target.id) },
                            onLike = { feed.like(n) },
                            onReact = { e -> feed.react(n.target, e) },
                            onRepost = { feed.repost(n) },
                            onQuote = { nav.onQuote(n.target.id) },
                            onTip = { nav.onTip(n.target) },
                            onShowTippers = { nav.onShowTippers(n.target) },
                            onBookmark = { feed.bookmark(n) },
                            onHashtag = nav.onHashtag,
                            onOpenNote = nav.onOpenThread,
                            onOpenMedia = nav.onOpenMedia,
                            onOpenVideo = nav.onOpenVideo,
                            names = names,
                            menu = menu,
                            replyContext = chain == null,
                            lineAbove = chain != null,
                        )
                    }
                    HorizontalDivider()
                }
                item { LaunchedEffect(state.notes.size) { feed.loadMore() } }
            }
            VisibleNotes(listState, feed::visible)
        }
    }
}
