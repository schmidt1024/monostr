package com.monostr.app.ui.lists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.nostr.model.ListAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ListEditViewModel @Inject constructor(session: NostrSession, handle: SavedStateHandle) : ViewModel() {
    private val ready = session.requireReady()
    val controller = ListEditController(
        handle.get<String>("address")?.let(ListAddress::parse), ready.pubkey, handle.get<String>("add"),
        ready.lists, ready.feedTabs, viewModelScope, writeScope = ready.engine.backgroundScope,
    )
    init { controller.start() }
}

/** Spec 2026-10-18 §2.4: route `list-edit?address={address}&add={add}`; [onDone] with true after a delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListEditScreen(onDone: (deleted: Boolean) -> Unit, vm: ListEditViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(state.message, snackbar, onUnmute = {}, onShown = vm.controller::clearMessage)
    LaunchedEffect(state.done) { if (state.done) onDone(state.deleted) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.list_create else R.string.list_edit_title)) },
                navigationIcon = { IconButton(onClick = { onDone(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    TextButton(onClick = vm.controller::save, enabled = state.canSave, modifier = Modifier.testTag("list-save")) { Text(stringResource(R.string.list_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.loading) return@Column
            OutlinedTextField(
                value = state.title, onValueChange = vm.controller::setTitle, singleLine = true,
                label = { Text(stringResource(R.string.list_title_label)) },
                supportingText = { Text("${state.title.length}/${ListEditController.MAX_TITLE}") },
                modifier = Modifier.fillMaxWidth().testTag("list-title"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.description, onValueChange = vm.controller::setDescription, minLines = 2,
                label = { Text(stringResource(R.string.list_description_label)) },
                supportingText = { Text("${state.description.length}/${ListEditController.MAX_DESCRIPTION}") },
                modifier = Modifier.fillMaxWidth().testTag("list-description"),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.list_private), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.list_private_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.private, onCheckedChange = vm.controller::setPrivate, modifier = Modifier.testTag("list-private"))
            }
            if (!state.isNew) {
                Spacer(Modifier.height(24.dp))
                TextButton(
                    onClick = vm.controller::askDelete, enabled = !state.saving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("list-delete"),
                ) { Text(stringResource(R.string.list_delete)) }
            }
        }
    }
    if (state.confirmPublic) {
        AlertDialog(
            onDismissRequest = vm.controller::dismissPublic,
            text = { Text(stringResource(R.string.list_public_text)) },
            confirmButton = { TextButton(onClick = vm.controller::confirmPublic, modifier = Modifier.testTag("list-public-confirm")) { Text(stringResource(R.string.list_public_confirm)) } },
            dismissButton = { TextButton(onClick = vm.controller::dismissPublic) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = vm.controller::dismissDelete,
            text = { Text(stringResource(R.string.list_delete_text)) },
            confirmButton = { TextButton(onClick = vm.controller::delete, modifier = Modifier.testTag("list-delete-confirm")) { Text(stringResource(R.string.list_delete_confirm)) } },
            dismissButton = { TextButton(onClick = vm.controller::dismissDelete) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
