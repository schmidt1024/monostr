package com.monostr.app.ui.tips

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.data.Presets
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.relativeTime
import com.monostr.nostr.model.Note
import kotlinx.coroutines.CancellationException

/** Spec 5.6: who tipped a note, how much, with the intent comment. */
@Composable
fun TippersDialog(note: Note, load: suspend (Note) -> List<TipperUi>, onDismiss: () -> Unit) {
    val items by produceState<List<TipperUi>?>(initialValue = null, note.id) {
        value = try {
            load(note)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.tippers_title)) },
        text = {
            when (val list = items) {
                null -> CircularProgressIndicator()
                else -> if (list.isEmpty()) Text(stringResource(R.string.tippers_empty)) else LazyColumn {
                    items(list) { t ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row {
                                Text(t.name ?: stringResource(R.string.tip_anonymous), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.tip_xmr_amount, Presets.format(t.amount)), style = MaterialTheme.typography.titleSmall)
                            }
                            if (t.comment.isNotBlank()) Text(t.comment, style = MaterialTheme.typography.bodySmall)
                            Text(relativeTime(t.createdAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    )
}
