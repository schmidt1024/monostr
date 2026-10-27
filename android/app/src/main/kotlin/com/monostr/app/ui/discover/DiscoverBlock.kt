package com.monostr.app.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.ui.common.Avatar
import com.monostr.nostr.model.ListAddress

/**
 * Spec 2026-10-27 §5 (variant A): per curated list a title with «Follow all», [DiscoverController.ROWS] rows with
 * avatar, name, one line of bio and a follow button, and «Show all». Added to the caller's LazyColumn; [showHint]
 * adds the feed's hint line with its dismiss button on top.
 */
fun LazyListScope.discoverItems(
    ui: DiscoverUi,
    showHint: Boolean,
    onDismiss: () -> Unit,
    onFollow: (String) -> Unit,
    onFollowAll: (ListAddress) -> Unit,
    onShowAll: (ListAddress) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRetry: () -> Unit,
) {
    if (showHint) item(key = "discover-hint") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.discover_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("discover-hint-dismiss")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.discover_dismiss))
            }
        }
    }
    when (ui.status) {
        DiscoverStatus.Loading -> item(key = "discover-loading") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
        DiscoverStatus.Failed -> item(key = "discover-failed") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.discover_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry, modifier = Modifier.testTag("discover-retry")) { Text(stringResource(R.string.discover_retry)) }
            }
        }
        DiscoverStatus.Ready -> for (section in ui.sections) {
            val d = section.address.d
            item(key = "discover-$d-title") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onFollowAll(section.address) }, enabled = !section.allFollowed, modifier = Modifier.testTag("discover-follow-all-$d")) {
                        Text(stringResource(if (section.allFollowed) R.string.discover_all_followed else R.string.discover_follow_all))
                    }
                }
            }
            for (row in section.rows) item(key = "discover-$d-${row.profile.pubkey}") {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenProfile(row.profile.pubkey) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(row.profile.picture)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.profile.shownName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        row.profile.about?.takeIf { it.isNotBlank() }?.let {
                            Text(it.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FollowPill(row.following, onClick = { onFollow(row.profile.pubkey) }, tag = "discover-follow-${row.profile.pubkey}")
                }
            }
            item(key = "discover-$d-all") {
                TextButton(onClick = { onShowAll(section.address) }, modifier = Modifier.padding(start = 4.dp).testTag("discover-show-all-$d")) {
                    Text(stringResource(R.string.discover_show_all))
                }
            }
        }
    }
}

/** «Follow» (filled) or «Following» (outlined, not clickable): unfollowing stays on the profile. */
@Composable
fun FollowPill(following: Boolean, onClick: () -> Unit, tag: String) {
    if (following) {
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.discover_following)) }
    } else {
        Button(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.discover_follow)) }
    }
}
