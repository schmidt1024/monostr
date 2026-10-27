package com.monostr.app.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.nostr.model.FollowSet

/** Spec 2026-10-18 §2.2: title (lock when private), member count, pin toggle; a tap opens the list. A locked list is shown disabled. */
@Composable
internal fun ListRow(set: FollowSet, pinned: Boolean, onOpen: () -> Unit, onPin: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val enabled = !set.locked
    Row(
        Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onOpen) else Modifier.alpha(0.5f))
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag("list-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(set.title.ifBlank { stringResource(R.string.list_untitled) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (set.private || set.locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.list_private), modifier = Modifier.size(16.dp))
                }
            }
            if (!set.locked) {
                Text(
                    pluralStringResource(R.plurals.list_members, set.members.size, set.members.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PinButton(pinned, onPin) // a pinned locked list stays unpinnable (spec §5)
        trailing?.invoke() // backlog 38: the drag grip of the own lists
    }
}

/** A pin in the accent colour when pinned, like X's pinned lists. */
@Composable
internal fun PinButton(pinned: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("list-pin")) {
        Icon(
            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = stringResource(if (pinned) R.string.list_unpin else R.string.list_pin),
            tint = if (pinned) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}
