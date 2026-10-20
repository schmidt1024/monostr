package com.monostr.app.ui.notifications

import com.monostr.app.data.dm.unreadTotalExcept
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.BottomTab
import com.monostr.app.ui.common.LoadingScreen
import com.monostr.app.ui.common.MonostrBottomBar
import com.monostr.app.ui.common.HideOnScrollFooter
import com.monostr.app.ui.common.ShowBarsWhenStuck
import com.monostr.app.ui.common.rememberHideOnScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.monostr.app.ui.common.asString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(session: NostrSession) : ViewModel() {
    private val ready = session.requireReady()
    /** Spec perf 3.6: the session's store; a reopen shows its last rows at once and reads the database behind them. */
    val store: NotificationsStore = ready.notificationsStore
    val dmUnread: StateFlow<Int> = ready.dmStore.unreadTotalExcept(ready.muted).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    init { store.open() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onFeed: () -> Unit, onSearch: () -> Unit, onBookmarks: () -> Unit, onMessages: () -> Unit, onOpenThread: (String) -> Unit, onOpenProfile: (String) -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val state by vm.store.state.collectAsStateWithLifecycle()
    val names by vm.store.names.collectAsStateWithLifecycle()
    val dmUnread by vm.dmUnread.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        // Android 13+: local notifications need this runtime permission; asked once, here, where the user expects it
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // everything shown counts as read once the list is on screen
    LaunchedEffect(state.loading, state.items.size) { if (!state.loading) vm.store.markRead() }
    val message = state.message?.asString()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.store.clearMessage() } }
    val hide = rememberHideOnScroll()
    val listState = rememberLazyListState()
    ShowBarsWhenStuck(hide, listState, hasList = !state.loading && state.rows.isNotEmpty())
    Scaffold(
        modifier = Modifier.nestedScroll(hide.connection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.notifications_title)) }) },
        bottomBar = { HideOnScrollFooter(hide) { MonostrBottomBar(BottomTab.NOTIFICATIONS, unread = 0, dmUnread = dmUnread, onFeed = onFeed, onSearch = onSearch, onBookmarks = onBookmarks, onMessages = onMessages, onNotifications = hide::show, windowInsets = WindowInsets(0)) } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) LoadingScreen()
        // like the home feed: pulling down at the top fetches from the relays
        else PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm.store::pull, modifier = Modifier.padding(padding).fillMaxSize().testTag("notifications-refresh")) {
            if (state.rows.isEmpty()) {
                // scrollable so the pull works on the empty state too; spec perf 3.6: shown at once, the relays asked behind it
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.notifications_empty), textAlign = TextAlign.Center)
                        if (state.syncing) {
                            Text(
                                stringResource(R.string.notifications_checking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp).testTag("notifications-checking"),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("notifications-list")) {
                    items(state.rows, key = { it.row.key }) { row ->
                        NotificationRowItem(row, names, onOpenThread = onOpenThread, onOpenProfile = onOpenProfile)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
