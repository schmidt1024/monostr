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
import androidx.compose.foundation.lazy.LazyColumn
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
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.BottomTab
import com.monostr.app.ui.common.LoadingScreen
import com.monostr.app.ui.common.MonostrBottomBar
import com.monostr.app.ui.common.asString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(session: NostrSession, settings: TipSettingsStore) : ViewModel() {
    private val ready = session.requireReady()
    val controller = NotificationsController(ready.notifications, ready.profiles, settings, viewModelScope, muted = ready.muted, me = ready.engine.pubkey)
    val dmUnread: StateFlow<Int> = ready.dmStore.unreadTotalExcept(ready.muted).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    init { controller.start() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onFeed: () -> Unit, onSearch: () -> Unit, onBookmarks: () -> Unit, onMessages: () -> Unit, onOpenThread: (String) -> Unit, onOpenProfile: (String) -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
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
    LaunchedEffect(state.loading, state.items.size) { if (!state.loading) vm.controller.markRead() }
    val message = state.message?.asString()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.controller.clearMessage() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.notifications_title)) }) },
        bottomBar = { MonostrBottomBar(BottomTab.NOTIFICATIONS, unread = 0, dmUnread = dmUnread, onFeed = onFeed, onSearch = onSearch, onBookmarks = onBookmarks, onMessages = onMessages, onNotifications = {}) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingScreen()
            state.rows.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.notifications_empty), textAlign = TextAlign.Center) }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("notifications-list")) {
                items(state.rows, key = { it.row.key }) { row ->
                    NotificationRowItem(row, names, onOpenThread = onOpenThread, onOpenProfile = onOpenProfile)
                    HorizontalDivider()
                }
            }
        }
    }
}
