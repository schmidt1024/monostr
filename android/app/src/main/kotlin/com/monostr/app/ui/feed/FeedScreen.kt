package com.monostr.app.ui.feed

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.monostr.app.ui.common.HideOnScrollHeader
import com.monostr.app.ui.common.rememberHideOnScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.monostr.app.ui.common.HideOnScrollFooter
import com.monostr.app.ui.common.RelayDot
import com.monostr.app.ui.common.muteUndoTag
import com.monostr.app.ui.tips.TipSheetHost
import com.monostr.app.ui.tips.TippersDialog
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.ListAddress
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
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
    onOpenList: (ListAddress) -> Unit = {},
    vm: FeedViewModel = hiltViewModel(),
) {
    val tabs by vm.tabs.state.collectAsStateWithLifecycle()
    val relays by vm.relayStates.collectAsStateWithLifecycle(initialValue = emptyList())
    val unread by vm.unread.collectAsStateWithLifecycle()
    val dmUnread by vm.dmUnread.collectAsStateWithLifecycle()
    val self by vm.self.collectAsStateWithLifecycle()
    val supportHint by vm.supportHint.collectAsStateWithLifecycle()
    val amountsPrompt by vm.amountsPrompt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val hide = rememberHideOnScroll()
    val current = tabs.current
    // spec 2026-10-18 §2.1: every tab keeps its own scroll position
    // (saveable: a thread opened from the feed and back, or a rotation, keeps the position like the single list did)
    val stateHolder = rememberSaveableStateHolder()
    var shownList by remember { mutableStateOf<LazyListState?>(null) }
    val feed: FeedController? = if (current is HomeTab.Following) vm.controller else tabs.feeds[current.key]
    // observed (not read once) so the "mute-undo" tag follows the shown feed's message
    val feedMessage by remember(feed) { feed?.state?.map { it.message } ?: flowOf(null) }.collectAsStateWithLifecycle(initialValue = null)
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    val nav = remember {
        NoteNav(onOpenThread, onOpenProfile, onReply, onQuote, onHashtag, onOpenMedia, onOpenVideo, onTip = { vm.tipSheet.open(it) }, onShowTippers = { tippersFor = it })
    }
    // spec 7: up to 30 entries away the list scrolls, beyond that it jumps (a long animation over hundreds of cards stutters and loads pictures for nothing)
    val toTop: () -> Unit = {
        hide.show()
        scope.launch {
            shownList?.let { ls -> if (ls.firstVisibleItemIndex > 30) ls.scrollToItem(0) else ls.animateScrollToItem(0) }
        }
        feed?.let { if (!it.state.value.refreshing) it.refresh() }
    }
    LaunchedEffect(current.key) { hide.show() }
    MessageSnackbar(tabs.message, snackbar, onUnmute = {}, onShown = vm.tabs::clearMessage)
    DeletionMessages(vm.deletions, snackbar)
    Scaffold(
        modifier = Modifier.nestedScroll(hide.connection),
        topBar = { HideOnScrollHeader(hide) { Column {
            TopAppBar(
                windowInsets = WindowInsets(0),
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
            // spec 2026-10-18 §2.1: only with a pinned list; it hides and returns with the other bars
            if (tabs.showRow) {
                FeedTabRow(
                    state = tabs,
                    onSelect = { i -> if (i == tabs.selected) toTop() else vm.tabs.select(i) },
                    onOpenList = onOpenList,
                    onUnpin = vm.tabs::unpin,
                    onMove = vm.tabs::move,
                )
            }
        } } },
        floatingActionButton = {
            // like X: the compose button turns see-through as the bars slide away on scrolling down, stays so while
            // the feed rests, and is solid again once scrolling up brings the bars back (tappable throughout)
            FloatingActionButton(
                onClick = onCompose,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                shape = CircleShape, // round with a quill, like X's compose button (0.11.10)
                modifier = Modifier.graphicsLayer { alpha = 1f - (1f - FAB_SCROLL_ALPHA) * hide.hiddenFraction }.testTag("fab-compose"),
            ) { Icon(FeatherGlyph, contentDescription = stringResource(R.string.feed_new_note)) }
        },
        bottomBar = { HideOnScrollFooter(hide) { MonostrBottomBar(BottomTab.FEED, unread = unread, dmUnread = dmUnread, onFeed = toTop, onSearch = onSearch, onBookmarks = onBookmarks, onMessages = onMessages, onNotifications = onNotifications, windowInsets = WindowInsets(0)) } },
        snackbarHost = { SnackbarHost(snackbar, Modifier.muteUndoTag(feedMessage)) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
          stateHolder.SaveableStateProvider(current.key) {
            val listState = rememberLazyListState()
            SideEffect { shownList = listState }
            val pinned = current as? HomeTab.Pinned
            when {
                pinned?.status == ListStatus.UNAVAILABLE -> UnavailableTab(onUnpin = { vm.tabs.unpin(pinned.address) })
                pinned?.status == ListStatus.LOCKED -> LockedTab(onUnlock = { vm.tabs.unlock(pinned.address) })
                pinned?.status == ListStatus.EMPTY -> CenteredText(stringResource(R.string.list_no_members), Modifier.testTag("feed-tab-empty"))
                feed == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val menu = remember(feed) { vm.menuFor(feed) }
                    FeedList(
                        feed = feed,
                        listState = listState,
                        menu = menu,
                        nav = nav,
                        emptyText = stringResource(if (pinned == null) R.string.feed_empty else R.string.list_feed_empty),
                        snackbar = snackbar,
                        hide = hide,
                        header = {
                            if (pinned == null && supportHint) {
                                item(key = "support-hint") {
                                    SupportHintCard(onSupport = { vm.dismissSupportHint(); onSupport() }, onDismiss = vm::dismissSupportHint)
                                }
                            }
                        },
                    )
                }
            }
          }
        }
    }
    TipSheetHost(vm.tipSheet)
    if (amountsPrompt.shown) {
        // back or a tap outside is no answer: the question comes again next start
        AlertDialog(
            onDismissRequest = vm::deferAmountsPrompt,
            title = { Text(stringResource(R.string.amounts_prompt_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.amounts_prompt_text))
                    if (amountsPrompt.failed) {
                        Text(
                            stringResource(R.string.setup_err_not_published), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp).testTag("amounts-prompt-error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.answerAmountsPrompt(true) }, enabled = !amountsPrompt.busy, modifier = Modifier.testTag("amounts-prompt-yes")) {
                    Text(stringResource(R.string.amounts_prompt_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerAmountsPrompt(false) }, enabled = !amountsPrompt.busy, modifier = Modifier.testTag("amounts-prompt-no")) {
                    Text(stringResource(R.string.amounts_prompt_no))
                }
            },
        )
    }
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

/** How see-through the compose button is while the bars are hidden. */
private const val FAB_SCROLL_ALPHA = 0.45f
