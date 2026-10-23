package com.monostr.app.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.nostr.model.ListAddress

/** Which moves the menu of the tab at [index] offers: (left, right). Following (0) is fixed, so the first list never moves left. */
internal fun tabMoves(index: Int, tabCount: Int): Pair<Boolean, Boolean> = (index > 1) to (index in 1 until tabCount - 1)

/** What a list tab says (spec perf 3.7): its title; while it loads a neutral word; "Untitled list" only for a list without one. */
internal sealed interface TabLabel {
    data class Title(val text: String) : TabLabel
    data object Loading : TabLabel
    data object Untitled : TabLabel
}

internal fun HomeTab.Pinned.label(): TabLabel = when {
    title.isNotBlank() -> TabLabel.Title(title)
    status == ListStatus.LOADING -> TabLabel.Loading
    else -> TabLabel.Untitled
}

/** Spec 2026-10-18 §2.1: "Following | list 1 | …", scrollable, like X; a long press on a list tab opens its menu. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FeedTabRow(
    state: HomeTabsState,
    onSelect: (Int) -> Unit,
    onOpenList: (ListAddress) -> Unit,
    onUnpin: (ListAddress) -> Unit,
    onMove: (ListAddress, Int) -> Unit,
) {
    // no 90 dp minimum: a list named with one emoji gets the same 16 dp sides as a word, not a wide gap
    PrimaryScrollableTabRow(selectedTabIndex = state.selected, edgePadding = 0.dp, minTabWidth = 0.dp, modifier = Modifier.testTag("feed-tabs")) {
        state.tabs.forEachIndexed { i, tab ->
            var menu by remember(tab.key) { mutableStateOf(false) }
            val isSelected = state.selected == i
            val label = when (tab) {
                HomeTab.Following -> stringResource(R.string.feed_tab_following)
                is HomeTab.Pinned -> when (val l = tab.label()) {
                    is TabLabel.Title -> l.text
                    TabLabel.Loading -> stringResource(R.string.feed_tab_list_loading)
                    TabLabel.Untitled -> stringResource(R.string.list_untitled)
                }
            }
            Box(
                Modifier
                    .combinedClickable(onClick = { onSelect(i) }, onLongClick = if (tab is HomeTab.Pinned) ({ menu = true }) else null)
                    .semantics { role = Role.Tab; selected = isSelected }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp)
                    .testTag("feed-tab-$i"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    // X greys the tabs that are not selected
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                if (tab is HomeTab.Pinned) {
                    val (left, right) = tabMoves(i, state.tabs.size)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.testTag("feed-tab-menu")) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_tab_menu_open)) }, onClick = { menu = false; onOpenList(tab.address) }, modifier = Modifier.testTag("feed-tab-menu-open"))
                        DropdownMenuItem(text = { Text(stringResource(R.string.list_unpin)) }, onClick = { menu = false; onUnpin(tab.address) }, modifier = Modifier.testTag("feed-tab-menu-unpin"))
                        if (left) DropdownMenuItem(text = { Text(stringResource(R.string.feed_tab_menu_left)) }, onClick = { menu = false; onMove(tab.address, -1) }, modifier = Modifier.testTag("feed-tab-menu-left"))
                        if (right) DropdownMenuItem(text = { Text(stringResource(R.string.feed_tab_menu_right)) }, onClick = { menu = false; onMove(tab.address, 1) }, modifier = Modifier.testTag("feed-tab-menu-right"))
                    }
                }
            }
        }
    }
}

/** Spec §2.1: a pinned list that was deleted or is found nowhere. */
@Composable
internal fun UnavailableTab(onUnpin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp).testTag("feed-tab-unavailable"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.list_unavailable), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onUnpin, modifier = Modifier.testTag("feed-tab-unpin")) { Text(stringResource(R.string.list_unpin)) }
    }
}

/** Spec §2.1: an own private list that could not be decrypted; a tap retries. */
@Composable
internal fun LockedTab(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onUnlock).padding(32.dp).testTag("feed-tab-locked"), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.list_locked))
        }
    }
}

@Composable
internal fun CenteredText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, textAlign = TextAlign.Center) }
}
