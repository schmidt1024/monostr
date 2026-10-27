package com.monostr.app.ui.lists

import com.monostr.app.ui.discover.FollowPill
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.DeletionMessages
import com.monostr.app.ui.common.HideOnScrollHeader
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.app.ui.common.NoteMenu
import com.monostr.app.ui.common.noteMenu
import com.monostr.app.ui.common.rememberHideOnScroll
import com.monostr.app.ui.feed.FeedController
import com.monostr.app.ui.feed.FeedList
import com.monostr.app.ui.feed.NoteNav
import com.monostr.app.ui.feed.listFeed
import com.monostr.app.ui.tips.TipSheetFactory
import com.monostr.app.ui.tips.TipSheetHost
import com.monostr.app.ui.tips.TippersDialog
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ListViewModel @Inject constructor(session: NostrSession, handle: SavedStateHandle, factory: TipSheetFactory) : ViewModel() {
    private val ready = session.requireReady()
    val address: ListAddress = checkNotNull(ListAddress.parse(checkNotNull(handle.get<String>("address"))))
    val controller = ListController(address, ready.pubkey, ready.lists, ready.feedTabs, ready.profiles, viewModelScope, writeScope = ready.engine.backgroundScope, follows = ready.follows, mute = ready.mute) { source, scope ->
        ready.listFeed(factory, source, scope)
    }
    val tipSheet = factory.tipSheet(viewModelScope)
    val decorations = factory.decorations(viewModelScope)
    val deletions = ready.deletions
    fun menuFor(feed: FeedController): NoteMenu = ready.noteMenu(delete = feed::delete, mute = feed::mute)
    init { controller.start() }
    override fun onCleared() { controller.close() }
}

/** Spec 2026-10-18 §2.3: route `list/{address}`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onEdit: (ListAddress) -> Unit,
    onOpenThread: (String) -> Unit,
    onReply: (String) -> Unit,
    onQuote: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    vm: ListViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val hide = rememberHideOnScroll()
    val listState = rememberLazyListState()
    val memberState = rememberLazyListState()
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    MessageSnackbar(state.message, snackbar, onUnmute = {}, onShown = vm.controller::clearMessage)
    DeletionMessages(vm.deletions, snackbar)
    val nav = remember {
        NoteNav(onOpenThread, onOpenProfile, onReply, onQuote, onHashtag, onOpenMedia, onOpenVideo, onTip = { vm.tipSheet.open(it) }, onShowTippers = { tippersFor = it })
    }
    val set = state.set
    Scaffold(
        modifier = Modifier.nestedScroll(hide.connection),
        topBar = { HideOnScrollHeader(hide) { Column {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(set?.title?.ifBlank { null } ?: stringResource(R.string.list_untitled), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    if (set != null) PinButton(state.pinned, vm.controller::togglePin)
                    if (state.isOwn && set != null && !set.locked) {
                        TextButton(onClick = { vm.address.let(onEdit) }, modifier = Modifier.testTag("list-edit")) { Text(stringResource(R.string.list_edit)) }
                    }
                },
            )
            if (set != null) {
                ListHeader(set, state.author, onOpenProfile)
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                    Tab(
                        selected = state.tab == ListTab.NOTES, onClick = { vm.controller.selectTab(ListTab.NOTES) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(stringResource(R.string.list_tab_notes)) }, modifier = Modifier.testTag("list-tab-notes"),
                    )
                    Tab(
                        selected = state.tab == ListTab.MEMBERS, onClick = { vm.controller.selectTab(ListTab.MEMBERS) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(stringResource(R.string.list_tab_members)) }, modifier = Modifier.testTag("list-tab-members"),
                    )
                }
            }
        } } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val feed = state.feed
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.unavailable || set == null -> Text(stringResource(R.string.list_unavailable), Modifier.align(Alignment.Center).padding(32.dp).testTag("list-unavailable"), textAlign = TextAlign.Center)
                set.locked -> Text(stringResource(R.string.list_locked), Modifier.align(Alignment.Center).clickable(onClick = vm.controller::unlock).padding(32.dp).testTag("list-locked"))
                state.tab == ListTab.MEMBERS -> MemberList(state.members, memberState, own = state.isOwn, onOpenProfile = onOpenProfile, onRemove = vm.controller::removeMember, followed = state.followed.takeIf { state.canFollow }, notFollowable = state.notFollowable, allFollowed = state.allFollowed, onFollow = vm.controller::follow, onFollowAll = vm.controller::followAll)
                feed == null -> Text(stringResource(R.string.list_no_members), Modifier.align(Alignment.Center).padding(32.dp))
                else -> {
                    val menu = remember(feed) { vm.menuFor(feed) }
                    FeedList(feed, listState, menu, nav, stringResource(R.string.list_feed_empty), snackbar, hide)
                }
            }
        }
    }
    TipSheetHost(vm.tipSheet)
    tippersFor?.let { TippersDialog(it, load = vm.decorations::tippers, onDismiss = { tippersFor = null }) }
}

@Composable
private fun ListHeader(set: FollowSet, author: Profile?, onOpenProfile: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        set.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val name = author?.shownName ?: Profile.shortPubkey(set.author)
        Row(Modifier.clickable { onOpenProfile(set.author) }.padding(vertical = 6.dp).testTag("list-author"), verticalAlignment = Alignment.CenterVertically) {
            Avatar(author?.picture, size = 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.list_by, name), style = MaterialTheme.typography.labelLarge)
            if (set.private) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.list_private), modifier = Modifier.size(16.dp))
            }
        }
        Text(pluralStringResource(R.plurals.list_members, set.members.size, set.members.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MemberList(
    members: List<Profile>, listState: LazyListState, own: Boolean, onOpenProfile: (String) -> Unit, onRemove: (String) -> Unit,
    /** Spec 2026-10-27 §5: someone else's list shows follow buttons and «Follow all»; null hides them. */
    followed: Set<String>? = null, notFollowable: Set<String> = emptySet(), allFollowed: Boolean = false,
    onFollow: (String) -> Unit = {}, onFollowAll: () -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize().testTag("list-members"), state = listState) {
        if (followed != null) item(key = "follow-all") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onFollowAll, enabled = !allFollowed, modifier = Modifier.testTag("list-follow-all")) {
                    Text(stringResource(if (allFollowed) R.string.discover_all_followed else R.string.discover_follow_all))
                }
            }
        }
        items(members, key = { it.pubkey }) { p ->
            Row(Modifier.fillMaxWidth().clickable { onOpenProfile(p.pubkey) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.picture)
                Spacer(Modifier.width(12.dp))
                Text(p.shownName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (followed != null && p.pubkey !in notFollowable) FollowPill(p.pubkey in followed, onClick = { onFollow(p.pubkey) }, tag = "list-member-follow-${p.pubkey.take(8)}")
                if (own) {
                    IconButton(onClick = { onRemove(p.pubkey) }, modifier = Modifier.testTag("list-member-remove")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.list_member_remove, p.shownName))
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
