package com.monostr.app.ui.search

import com.monostr.app.data.dm.unreadTotalExcept
import com.monostr.app.ui.common.MentionResolver
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.RecentSearchesStore
import com.monostr.app.data.SearchRelayStore
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.BottomTab
import com.monostr.app.ui.common.DeletionMessages
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.app.ui.common.MonostrBottomBar
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.common.noteMenu
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.muteUndoTag
import com.monostr.app.ui.feed.target
import com.monostr.app.ui.tips.TipSheetFactory
import com.monostr.app.ui.tips.TipSheetHost
import com.monostr.app.ui.tips.TippersDialog
import com.monostr.nostr.Npub
import com.monostr.nostr.model.Note
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(session: NostrSession, factory: TipSheetFactory, searchRelays: SearchRelayStore, recentSearches: RecentSearchesStore, handle: SavedStateHandle) : ViewModel() {
    private val ready = session.requireReady()
    val decorations = factory.decorations(viewModelScope)
    val bookmarks = BookmarkActions(ready.bookmarks, viewModelScope, background = ready.engine.backgroundScope)
    val controller = SearchController(ready.search, ready.profiles, ready.publish, decorations, bookmarks, searchRelays.relays, viewModelScope, mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default), deletions = ready.deletions, visibility = ready.visibility, afterDelete = ready.afterNoteDelete, mute = ready.mute, muteScope = ready.engine.backgroundScope, recentSearches = recentSearches)
    val deletions = ready.deletions
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)
    val tipSheet = factory.tipSheet(viewModelScope)
    val dmUnread: StateFlow<Int> = ready.dmStore.unreadTotalExcept(ready.muted).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    init {
        controller.start()
        handle.get<String>("q")?.let { controller.onQueryChange(it); controller.submit() }
    }
    override fun onCleared() { controller.close() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onFeed: () -> Unit,
    onBookmarks: () -> Unit,
    onMessages: () -> Unit,
    onNotifications: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: (String) -> Unit,
    onSettings: () -> Unit,
    onOpenMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    vm: SearchViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
    val dmUnread by vm.dmUnread.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { vm.controller.nav.collect { when (it) { is SearchNav.Profile -> onOpenProfile(it.pubkey); is SearchNav.Thread -> onOpenThread(it.noteId) } } }
    LaunchedEffect(Unit) { if (state.query.isEmpty()) runCatching { focus.requestFocus() } }
    MessageSnackbar(state.message, snackbar, onUnmute = vm.controller::unmute, onShown = vm.controller::clearMessage)
    DeletionMessages(vm.deletions, snackbar)
    val hashtag: (String) -> Unit = { vm.controller.onQueryChange("#$it"); vm.controller.submit() }
    Scaffold(
        topBar = {
            // deviation from the brief: an OutlinedTextField inside TopAppBar(title = …) clips
            // against the app bar's fixed height; a plain Row keeps the same field and tags.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query, onValueChange = vm.controller::onQueryChange, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.controller.onQueryChange(""); vm.controller.submit() }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear)) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.controller.submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search-field"),
                )
            }
        },
        bottomBar = { MonostrBottomBar(BottomTab.SEARCH, unread = 0, dmUnread = dmUnread, onFeed = onFeed, onSearch = {}, onBookmarks = onBookmarks, onMessages = onMessages, onNotifications = onNotifications) },
        snackbarHost = { SnackbarHost(snackbar, Modifier.muteUndoTag(state.message)) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("search-list")) {
            if (state.noSearchRelays && state.mode == SearchMode.TEXT) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.search_no_relays), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onSettings) { Text(stringResource(R.string.search_open_settings)) }
                    }
                }
            }
            if (state.loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            state.error?.let { err -> item { Text(err.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
            when (state.mode) {
                // spec 11.4 §2: the last searches, only while the field is empty; typing hides them
                SearchMode.IDLE -> if (state.recent.isNotEmpty() && state.query.isBlank()) {
                    item(key = "recent-title") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.search_recent_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm.controller::clearRecent, modifier = Modifier.testTag("search-recent-clear")) { Text(stringResource(R.string.search_recent_clear)) }
                        }
                    }
                    itemsIndexed(state.recent, key = { _, q -> "rq:$q" }) { i, q ->
                        val remove = stringResource(R.string.search_recent_remove)
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.controller.searchRecent(q) }.padding(start = 16.dp).testTag("search-recent-$i"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.controller.removeRecent(q) }, modifier = Modifier.testTag("search-recent-remove-$i")) {
                                Icon(Icons.Filled.Close, contentDescription = remove)
                            }
                        }
                    }
                }
                SearchMode.TEXT -> {
                    if (state.people.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_people)) }
                        items(state.people, key = { "p" + it.pubkey }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenProfile(p.pubkey) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("search-person-${p.pubkey.take(8)}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(p.picture)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(p.shownName, style = MaterialTheme.typography.titleSmall)
                                    Text(p.nip05 ?: Npub.short(p.pubkey), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (state.notes.isNotEmpty()) item { SectionHeader(stringResource(R.string.search_notes)) }
                    noteItems(state, onOpenThread, onOpenProfile, onReply, hashtag, vm, onShowTippers = { tippersFor = it }, onOpenMedia = onOpenMedia, onOpenVideo = onOpenVideo, onQuote = onQuote, names = names)
                    if (state.searched && !state.loading && state.people.isEmpty() && state.notes.isEmpty()) item { EmptyHint() }
                }
                SearchMode.HASHTAG -> {
                    item { SectionHeader("#" + (state.hashtag ?: "")) }
                    noteItems(state, onOpenThread, onOpenProfile, onReply, hashtag, vm, onShowTippers = { tippersFor = it }, onOpenMedia = onOpenMedia, onOpenVideo = onOpenVideo, onQuote = onQuote, names = names)
                    if (state.searched && !state.loading && state.notes.isEmpty()) item { EmptyHint() }
                    if (state.notes.isNotEmpty()) item { LaunchedEffect(state.notes.size) { vm.controller.loadMore() } }
                }
            }
        }
    }
    TipSheetHost(vm.tipSheet)
    tippersFor?.let { TippersDialog(it, load = vm.decorations::tippers, onDismiss = { tippersFor = null }) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.search_empty)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.noteItems(
    state: SearchUiState,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: (String) -> Unit,
    onHashtag: (String) -> Unit,
    vm: SearchViewModel,
    onShowTippers: (Note) -> Unit,
    onOpenMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    names: Map<String, String> = emptyMap(),
) {
    items(state.notes, key = { it.note.id }) { n ->
        NoteCard(
            note = n,
            onOpen = { onOpenThread(n.target.id) },
            onOpenProfile = onOpenProfile,
            onReply = { onReply(n.target.id) },
            onLike = { vm.controller.like(n.target) },
            onRepost = { vm.controller.repost(n.target) },
            onQuote = { onQuote(n.target.id) },
            onTip = { vm.tipSheet.open(n.target) },
            onShowTippers = { onShowTippers(n.target) },
            onHashtag = onHashtag,
            onOpenNote = onOpenThread,
            onBookmark = { vm.controller.bookmark(n.target) },
            onOpenMedia = onOpenMedia,
            onOpenVideo = onOpenVideo,
            names = names,
            menu = vm.menu,
        )
        HorizontalDivider()
    }
}
