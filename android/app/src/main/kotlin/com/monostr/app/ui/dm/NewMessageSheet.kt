package com.monostr.app.ui.dm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.monostr.app.R
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.GuardedModalBottomSheet
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.Npub
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.delay

/**
 * «New message» of the Messages tab (UX review 2026-11-26, backlog 115): a chat could only be started from a profile.
 * [search] looks through the people already on this phone, nothing goes to a search relay; a pick opens the chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageSheet(search: suspend (String) -> List<Profile>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val found by produceState<List<Profile>?>(null, query) {
        if (query.isBlank()) { value = null; return@produceState }
        delay(SEARCH_DELAY_MS) // not one lookup per key
        value = runCatching { search(query) }.getOrDefault(emptyList())
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    GuardedModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("new-message"), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            Text(stringResource(R.string.messages_new), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.heightIn(min = Spacing.s))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.messages_new_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("new-message-query"),
                shape = MaterialTheme.shapes.medium,
            )
            val rows = found
            if (rows != null && rows.isEmpty()) {
                Text(stringResource(R.string.messages_new_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.l))
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(rows.orEmpty(), key = { it.pubkey }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(p.pubkey) }.padding(vertical = Spacing.m).testTag("new-message-${p.pubkey.take(8)}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(p.picture)
                        Spacer(Modifier.width(Spacing.m))
                        Column(Modifier.weight(1f)) {
                            Text(p.shownName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Npub.short(p.pubkey), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private const val SEARCH_DELAY_MS = 200L
