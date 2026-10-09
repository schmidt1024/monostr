package com.monostr.app.ui.dm

import com.monostr.app.data.dm.unreadTotalExcept
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.BottomTab
import com.monostr.app.ui.common.MonostrBottomBar
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(session: NostrSession) : ViewModel() {
    private val ready = session.requireReady()
    val controller = ConversationsController(
        ready.dmStore, ready.profiles, ready.feed, ready.dms.state, ready.dmStore.pendingCount(),
        onRefresh = { ready.dms.sync(interactive = true) },
        onUnlock = { ready.dms.drainPending() },
        scope = viewModelScope,
        muted = ready.muted,
    )
    val dmUnread: StateFlow<Int> = ready.dmStore.unreadTotalExcept(ready.muted).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    init {
        ready.dms.adoptOwnList() // idempotent; the sync itself runs from session start
        controller.start()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onFeed: () -> Unit,
    onSearch: () -> Unit,
    onBookmarks: () -> Unit,
    onNotifications: () -> Unit,
    onOpenChat: (String) -> Unit,
    vm: ConversationsViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val dmUnread by vm.dmUnread.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var requestsOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        // Android 13+: DM notifications need this runtime permission; asked here, where the user expects messages
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val message = state.message?.asString()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.controller.clearMessage() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.messages_title)) }) },
        bottomBar = { MonostrBottomBar(BottomTab.MESSAGES, unread = 0, dmUnread = dmUnread, onFeed = onFeed, onSearch = onSearch, onBookmarks = onBookmarks, onMessages = {}, onNotifications = onNotifications) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm.controller::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                // always a LazyColumn, so pull-to-refresh also works on the empty state
                LazyColumn(Modifier.fillMaxSize().testTag("messages-list")) {
                    items(state.authFailed.sorted(), key = { "auth-$it" }) { relay ->
                        Text(
                            stringResource(R.string.messages_auth_failed, relay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("messages-auth-hint"),
                        )
                    }
                    items(state.authRejected.toList().sortedBy { it.first }, key = { "auth-rejected-${it.first}" }) { (relay, reason) ->
                        Text(
                            stringResource(R.string.messages_auth_rejected, relay, reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("messages-auth-rejected"),
                        )
                    }
                    if (state.pending > 0) {
                        item(key = "pending") {
                            Text(
                                pluralStringResource(R.plurals.messages_pending, state.pending, state.pending), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable(onClick = vm.controller::unlockPending).padding(horizontal = 16.dp, vertical = 12.dp).testTag("messages-pending"),
                            )
                            HorizontalDivider()
                        }
                    }
                    if (state.items.isEmpty() && state.requests.isEmpty()) {
                        item(key = "empty") {
                            Box(Modifier.fillParentMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.messages_empty), textAlign = TextAlign.Center) }
                        }
                    }
                    conversationRows(state.items, onOpenChat)
                    if (state.requests.isNotEmpty()) {
                        item(key = "requests") {
                            Row(
                                Modifier.fillMaxWidth().clickable { requestsOpen = !requestsOpen }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("messages-requests"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(pluralStringResource(R.plurals.messages_requests, state.requests.size, state.requests.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Icon(if (requestsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                            }
                            HorizontalDivider()
                        }
                        if (requestsOpen) conversationRows(state.requests, onOpenChat)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.conversationRows(rows: List<ConversationUi>, onOpenChat: (String) -> Unit) {
    items(rows, key = { it.conversation.peer }) { row -> ConversationRow(row, onOpenChat) }
}

@Composable
private fun ConversationRow(row: ConversationUi, onOpenChat: (String) -> Unit) {
    val c = row.conversation
    Row(
        Modifier.fillMaxWidth().clickable { onOpenChat(c.peer) }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("conversation-${c.peer.take(8)}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.profile.picture)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.profile.shownName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal,
            )
            Text(c.lastText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(relativeTime(c.lastAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
