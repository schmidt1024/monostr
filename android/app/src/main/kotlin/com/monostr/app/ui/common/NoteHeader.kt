package com.monostr.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.target
import com.monostr.nostr.model.Note
import com.monostr.nostr.Npub
import kotlinx.coroutines.launch

/** What the three-dot menu of a note can do (spec 2); the screens build it from their ViewModel. */
class NoteMenu(
    /** `nostr:nevent1…` of the note. */
    val link: suspend (Note) -> String,
    val delete: (Note) -> Unit,
    /** Whether deleting the note also removes pictures from the configured media server (the dialog says so, spec 4.1). */
    val hasServerPictures: (Note) -> Boolean = { false },
    /** Mutes the note's author (spec 9.4). */
    val mute: (Note) -> Unit = {},
    /** Whether the menu offers muting the note's author: someone else's note and a writable mute list (spec 9.1). */
    val canMute: (Note) -> Boolean = { false },
)

/** Spec 11.5 §3: avatar, name and handle of the focused note; the menu at the right. */
@Composable
fun NoteDetailHeader(note: NoteUi, onOpenProfile: (String) -> Unit, menu: NoteMenu?) {
    val shown = note.target
    val author = if (note.note.isRepost) note.repostedAuthor else note.author
    Row(verticalAlignment = Alignment.CenterVertically) {
        val openProfile = stringResource(R.string.note_open_profile)
        IconButton(onClick = { onOpenProfile(shown.author) }, modifier = Modifier.size(48.dp).semantics { contentDescription = openProfile }.testTag("note-avatar-${shown.id.take(8)}")) {
            Avatar(author.picture, size = 48.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable { onOpenProfile(shown.author) }) {
            Text(author.shownName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // the handle: the kind-0 nip05 claim (not verified here, shown as SearchScreen shows it), else the short npub
            Text(author.nip05?.takeIf { it.isNotBlank() } ?: Npub.short(shown.author), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu != null) NoteMenuButton(note, shown, author.shownName, menu)
    }
}

/** Name · time · menu of the shown note (the original of a repost), in one line, without the avatar (the list card keeps the avatar in its own column, spec 11.5 §2). */
@Composable
fun NoteHeaderLine(note: NoteUi, onOpen: () -> Unit, onOpenProfile: (String) -> Unit, menu: NoteMenu?) {
    val shown = note.target
    val shownAuthor = if (note.note.isRepost) note.repostedAuthor else note.author
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            // like X: the name leads to the author, the time and the free space to the note (0.11.9)
            Text(shownAuthor.shownName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).clickable { onOpenProfile(shown.author) }.testTag("note-name-${shown.id.take(8)}"))
            Spacer(Modifier.width(6.dp))
            Text(relativeTime(shown.createdAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.testTag("note-reltime-${shown.id.take(8)}"))
        }
        if (menu != null) NoteMenuButton(note, shown, shownAuthor.shownName, menu)
    }
}

@Composable
private fun NoteMenuButton(note: NoteUi, shown: Note, authorName: String, menu: NoteMenu) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copied = stringResource(R.string.note_copied)
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
        // Android 13+ confirms a copy itself
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }
    Box {
        // IconButton keeps the 48 dp target
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("note-menu-${shown.id.take(8)}")) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.note_menu), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.note_menu_copy_link)) },
                onClick = { open = false; scope.launch { runCatching { menu.link(shown) }.onSuccess(::copy) } },
                modifier = Modifier.testTag("note-menu-copy-link"),
            )
            if (shown.content.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.note_menu_copy_text)) },
                    onClick = { open = false; copy(shown.content) },
                    modifier = Modifier.testTag("note-menu-copy-text"),
                )
            }
            if (!note.isOwn && menu.canMute(shown)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mute_account, authorName)) },
                    onClick = { open = false; menu.mute(shown) },
                    modifier = Modifier.testTag("note-menu-mute"),
                )
            }
            if (note.isOwn) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.note_menu_delete), color = if (note.deleting) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error) },
                    onClick = { open = false; confirm = true },
                    enabled = !note.deleting,
                    modifier = Modifier.testTag("note-menu-delete"),
                )
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.note_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.note_delete_text))
                    if (menu.hasServerPictures(shown)) {
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.note_delete_pictures))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; menu.delete(shown) }, modifier = Modifier.testTag("note-delete-confirm")) {
                    Text(stringResource(R.string.note_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
