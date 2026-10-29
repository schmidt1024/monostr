package com.monostr.app.ui.dm

import com.monostr.app.ui.common.MentionResolver
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.NoteText
import com.monostr.app.ui.common.AddressCard
import com.monostr.app.ui.common.LocalOpenArticle
import com.monostr.app.ui.common.LocalAllowForeign
import androidx.compose.runtime.CompositionLocalProvider
import com.monostr.nostr.model.AddressRef
import com.monostr.app.ui.common.dmAddressRef
import com.monostr.app.ui.common.QuoteCard
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.relativeTime
import com.monostr.app.ui.media.NoteMediaRow
import com.monostr.nostr.model.NoteMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@HiltViewModel
class ChatViewModel @Inject constructor(session: NostrSession, handle: SavedStateHandle) : ViewModel() {
    private val ready = session.requireReady()
    val controller = ChatController(
        checkNotNull(handle["pubkey"]), ready.pubkey, ready.dmStore, ready.profiles,
        send = { peer, text -> ready.dms.send(peer, text) },
        retry = { ready.dms.retry(it) },
        scope = viewModelScope,
        now = { System.currentTimeMillis() / 1000 },
        mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default),
    )
    init {
        ready.dms.adoptOwnList() // idempotent; a chat opened from a profile settles the own inbox list too
        controller.start()
    }
}

/**
 * Spec 8: a DM sender must not pick relays the app contacts. Every article link in a bubble (a card, or the inline «event»
 * link) opens the reader without the naddr's hints and with `foreign = false`: only the user's own relays are asked.
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenMedia: (urls: List<String>, index: Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val parent = LocalOpenArticle.current
    val open = remember(parent) { parent?.let { p -> { ref: AddressRef, _: Boolean -> p(dmAddressRef(ref), false) } } }
    CompositionLocalProvider(LocalOpenArticle provides open, LocalAllowForeign provides false) {
        ChatScreenContent(onBack, onOpenProfile, onOpenThread, onHashtag, onOpenMedia, onOpenVideo, vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenMedia: (urls: List<String>, index: Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    vm: ChatViewModel,
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var text by rememberSaveable { mutableStateOf("") }
    val message = state.message?.asString()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.controller.clearMessage() } }
    LifecycleStartEffect(Unit) {
        vm.controller.setVisible(true)
        onStopOrDispose { vm.controller.setVisible(false) }
    }
    // spec perf2 D5: newest first in a reversed list, so a chat opens at the bottom without scrolling through it
    val shown = remember(state.messages) { state.messages.asReversed() }
    val newest = state.messages.lastOrNull()?.message
    LaunchedEffect(newest?.rumorId) {
        // follow a new message only when the user is at the bottom (index 1 = the previous newest, pushed up) or sent it
        if (newest != null && (newest.outgoing || listState.firstVisibleItemIndex <= 1)) listState.animateScrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_OLDER_AHEAD
        }.distinctUntilChanged().filter { it }.collect { vm.controller.loadOlder() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        Modifier.clickable { onOpenProfile(state.peer.pubkey) }.testTag("chat-title"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(state.peer.picture)
                        Spacer(Modifier.width(12.dp))
                        Text(state.peer.shownName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyColumn(
                        state = listState, reverseLayout = true,
                        modifier = Modifier.fillMaxSize().testTag("chat-list"),
                        // bottom-aligned: a short chat sits right above the input like in common messengers
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                    ) {
                        items(shown, key = { it.message.rumorId }) { item ->
                            Bubble(item, names, onOpenProfile, onOpenThread, onHashtag, onOpenMedia, onOpenVideo, onRetry = vm.controller::retry)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, maxLines = 5,
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    modifier = Modifier.weight(1f).testTag("chat-input"),
                )
                IconButton(
                    onClick = { vm.controller.send(text); text = "" },
                    enabled = text.isNotBlank() && !state.sending,
                    modifier = Modifier.testTag("chat-send"),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send)) }
            }
        }
    }
}

@Composable
private fun Bubble(
    item: ChatMessageUi,
    names: Map<String, String>,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenMedia: (List<String>, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val m = item.message
    val note = item.note
    val own = m.outgoing
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalAlignment = if (own) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (note.displayContent.isNotBlank()) {
                    NoteText(note.displayContent, onOpen = {}, onHashtag = onHashtag, onOpenProfile = onOpenProfile, onOpenNote = onOpenThread, names = names)
                }
                note.quotedId?.let { qid ->
                    // spec 8: no hint relays from a DM (they would connect to relays the sender chose); DB and own relays only
                    QuoteCard(qid, emptyList(), names, onOpenNote = onOpenThread, onOpenProfile = onOpenProfile, onHashtag = onHashtag, modifier = Modifier.padding(top = 6.dp))
                }
                // spec 8: no hint relays from a DM, as for the quote above
                note.addressRef?.let { AddressCard(dmAddressRef(it), names, onOpenProfile = onOpenProfile, modifier = Modifier.padding(top = 6.dp), sensitive = note.isSensitive, allowForeign = false) }
                NoteMediaRow(note, onOpenMedia = { i -> openPictures(note.media, i, onOpenMedia) }, onOpenVideo = onOpenVideo)
                Row(Modifier.align(Alignment.End).testTag("chat-status-${m.rumorId.take(8)}"), verticalAlignment = Alignment.CenterVertically) {
                    Text(relativeTime(m.createdAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (own) {
                        Spacer(Modifier.width(4.dp))
                        StatusIcon(m.status)
                    }
                }
            }
        }
        if (own && m.status == DmStatus.FAILED) {
            TextButton(onClick = { onRetry(m.rumorId) }) { Text(stringResource(R.string.chat_retry)) }
        }
    }
}

@Composable
private fun StatusIcon(status: DmStatus) {
    val iconSize = Modifier.size(14.dp)
    when (status) {
        DmStatus.SENDING -> Icon(Icons.Outlined.Schedule, contentDescription = stringResource(R.string.chat_status_sending), modifier = iconSize)
        DmStatus.SENT -> Icon(Icons.Filled.Check, contentDescription = null, modifier = iconSize)
        DmStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.chat_status_failed), tint = MaterialTheme.colorScheme.error, modifier = iconSize)
        DmStatus.RECEIVED -> Unit
    }
}

/** The viewer pages pictures only: [index] points into all of the bubble's media, videos included. */
private fun openPictures(media: List<NoteMedia>, index: Int, onOpenMedia: (List<String>, Int) -> Unit) {
    val pictures = media.filter { it.kind == NoteMedia.Kind.IMAGE }
    val page = pictures.indexOfFirst { it.url == media.getOrNull(index)?.url }.coerceAtLeast(0)
    if (pictures.isNotEmpty()) onOpenMedia(pictures.map { it.url }, page)
}

/** Older messages are asked for when the last visible row is this close to the oldest loaded one. */
private const val LOAD_OLDER_AHEAD = 10
