package com.monostr.app.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R

/** Spec 2026-10-18 §2.5: one checkbox per own list, "New list…" at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListSheet(controller: AddToListController, onNewList: () -> Unit, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("add-to-list-sheet")) {
        Text(stringResource(R.string.profile_menu_lists), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val rows = state.rows
        if (rows == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(rows, key = { it.set.address.toString() }) { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = row.checked, enabled = !row.set.locked, role = Role.Checkbox, onValueChange = { controller.toggle(row) })
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("add-to-list-${row.set.address.d}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = row.checked, onCheckedChange = null, enabled = !row.set.locked)
                        Spacer(Modifier.width(12.dp))
                        Text(row.set.title.ifBlank { stringResource(R.string.list_untitled) }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.set.private) Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.list_private), modifier = Modifier.size(16.dp))
                    }
                }
                item(key = "add-to-list-create") {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onNewList).padding(horizontal = 16.dp, vertical = 14.dp).testTag("add-to-list-create"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.add_to_list_new))
                    }
                }
            }
        }
    }
}
