package com.monostr.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.accounts.LocalOpenAccounts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Where the gear and the avatar of a tab's top bar lead; the logged-in nav host provides it. */
class TabNav(val onSettings: () -> Unit, val onOwnProfile: () -> Unit)

val LocalTabNav = staticCompositionLocalOf { TabNav({}, {}) }

/** The own picture for [TabBarActions]; whether there are more accounts is deliberately not shown here, the feed's badge says it. */
@HiltViewModel
class TabBarViewModel @Inject constructor(session: NostrSession) : ViewModel() {
    private val ready = session.requireReady()
    val picture: StateFlow<String?> = ready.profiles.observe(ready.pubkey).map { it.picture }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Settings and the own profile on every tab's top bar, as on the feed (UX review 2026-11-26, backlog 115; owner: best
 * practice): from Messages, Notifications or Bookmarks the way to Settings or to another account led through the feed.
 * A tap on the avatar opens the own profile, a long press the account switcher.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabBarActions(vm: TabBarViewModel = hiltViewModel()) {
    val nav = LocalTabNav.current
    val picture by vm.picture.collectAsStateWithLifecycle()
    IconButton(onClick = nav.onSettings, modifier = Modifier.testTag("tab-settings")) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.feed_settings)) }
    val label = stringResource(R.string.feed_profile)
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .combinedClickable(role = Role.Button, onClick = nav.onOwnProfile, onLongClick = LocalOpenAccounts.current, onLongClickLabel = stringResource(R.string.accounts_switch_label))
            .testTag("tab-profile"),
        contentAlignment = Alignment.Center,
    ) {
        val url = picture
        if (url.isNullOrBlank()) Icon(Icons.Outlined.AccountCircle, contentDescription = label, modifier = Modifier.size(28.dp))
        else AsyncImage(model = url, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp).clip(CircleShape))
    }
}
