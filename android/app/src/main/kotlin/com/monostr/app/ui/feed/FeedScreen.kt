package com.monostr.app.ui.feed

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.common.BottomTab
import com.monostr.app.ui.theme.FeatherGlyph
import com.monostr.app.ui.common.DeletionMessages
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.app.ui.common.MonostrBottomBar
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.common.RelayDot
import com.monostr.app.ui.common.VisibleNotes
import com.monostr.app.ui.common.muteUndoTag
import com.monostr.app.ui.tips.TipSheetHost
import com.monostr.app.ui.tips.TippersDialog
import com.monostr.nostr.model.Note
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onCompose: () -> Unit,
    onReply: (String) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onMessages: () -> Unit,
    onNotifications: () -> Unit,
    onHashtag: (String) -> Unit,
    onBookmarks: () -> Unit,
    onOpenMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    onSupport: () -> Unit = {},
    vm: FeedViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
    val relays by vm.relayStates.collectAsStateWithLifecycle(initialValue = emptyList())
    val unread by vm.unread.collectAsStateWithLifecycle()
    val dmUnread by vm.dmUnread.collectAsStateWithLifecycle()
    val self by vm.self.collectAsStateWithLifecycle()
    val supportHint by vm.supportHint.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // spec 7: up to 30 entries away the list scrolls, beyond that it jumps (a long animation over hundreds of cards stutters and loads pictures for nothing)
    val toTop: () -> Unit = {
        scope.launch {
            if (listState.firstVisibleItemIndex > 30) listState.scrollToItem(0) else listState.animateScrollToItem(0)
        }
        if (!state.refreshing) vm.controller.refresh()
    }
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    MessageSnackbar(state.message, snackbar, onUnmute = vm.controller::unmute, onShown = vm.controller::clearMessage)
    DeletionMessages(vm.deletions, snackbar)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val toTopLabel = stringResource(R.string.feed_to_top)
                    // the ripple covers the mark only, not the whole bar; the target is 48 dp high
                    Box(
                        Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = toTopLabel, onClick = toTop)
                            .semantics { contentDescription = toTopLabel }
                            .testTag("feed-wordmark")
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // the wordmark, not the app name as text: the brand is always the lowercase "monostr" glyphs
                        Image(
                            painter = painterResource(R.drawable.ic_wordmark),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.height(22.dp),
                        )
                    }
                },
                actions = {
                    // from the right: own avatar, settings, relay indicator (bookmarks live in the bottom bar since 11.1)
                    RelayDot(relays.map { it.state })
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.feed_settings)) }
                    val selfLabel = stringResource(R.string.feed_profile)
                    IconButton(onClick = { onOpenProfile(vm.selfPubkey) }, modifier = Modifier.testTag("feed-profile")) {
                        val picture = self.picture
                        if (picture.isNullOrBlank()) {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = selfLabel, modifier = Modifier.size(28.dp))
                        } else {
                            AsyncImage(model = picture, contentDescription = selfLabel, contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp).clip(CircleShape))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCompose,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                shape = CircleShape, // round with a quill, like X's compose button (0.11.10)
                modifier = Modifier.testTag("fab-compose"),
            ) { Icon(FeatherGlyph, contentDescription = stringResource(R.string.feed_new_note)) }
        },
        bottomBar = { MonostrBottomBar(BottomTab.FEED, unread = unread, dmUnread = dmUnread, onFeed = toTop, onSearch = onSearch, onBookmarks = onBookmarks, onMessages = onMessages, onNotifications = onNotifications) },
        snackbarHost = { SnackbarHost(snackbar, Modifier.muteUndoTag(state.message)) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm.controller::refresh, modifier = Modifier.padding(padding).fillMaxSize().testTag("feed-refresh")) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.notes.isEmpty()) {
                // scrollable so pull-to-refresh works on the empty state too
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.feed_empty), textAlign = TextAlign.Center) }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("feed-list")) {
                    if (supportHint) {
                        item(key = "support-hint") {
                            SupportHintCard(onSupport = { vm.dismissSupportHint(); onSupport() }, onDismiss = vm::dismissSupportHint)
                        }
                    }
                    items(state.notes, key = { it.note.id }) { n ->
                        NoteCard(
                            note = n,
                            onOpen = { onOpenThread(n.target.id) },
                            onOpenProfile = onOpenProfile,
                            onReply = { onReply(n.target.id) },
                            onLike = { vm.controller.like(n) },
                            onRepost = { vm.controller.repost(n) },
                            onQuote = { onQuote(n.target.id) },
                            onTip = { vm.tipSheet.open(n.target) },
                            onShowTippers = { tippersFor = n.target },
                            onBookmark = { vm.controller.bookmark(n) },
                            onHashtag = onHashtag,
                            onOpenNote = onOpenThread,
                            onOpenMedia = onOpenMedia,
                            onOpenVideo = onOpenVideo,
                            names = names,
                            menu = vm.menu,
                        )
                        HorizontalDivider()
                    }
                    item { LaunchedEffect(state.notes.size) { vm.controller.loadMore() } }
                }
                VisibleNotes(listState, vm.controller::visible)
            }
        }
    }
    TipSheetHost(vm.tipSheet)
    tippersFor?.let { TippersDialog(it, load = vm.decorations::tippers, onDismiss = { tippersFor = null }) }
}

/** Spec 4: "Enjoying Monostr?" with Support and Hide; both end it for good. */
@Composable
private fun SupportHintCard(onSupport: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("support-hint")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.support_hint_title), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("support-hint-dismiss")) { Text(stringResource(R.string.support_hint_dismiss)) }
                TextButton(onClick = onSupport, modifier = Modifier.testTag("support-hint-action")) { Text(stringResource(R.string.support_hint_action)) }
            }
        }
    }
}
