package com.monostr.app.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.PrefsDmSettingsStore
import com.monostr.app.data.RelayStore
import com.monostr.app.session.NewKey
import com.monostr.app.session.NostrSession
import com.monostr.app.session.PendingSetup
import com.monostr.app.session.SessionState
import com.monostr.app.ui.common.SecureWindow
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.copySecret
import com.monostr.app.ui.monero.NSEC_CLIP_LABEL
import com.monostr.app.ui.monero.SeedClipboardClear
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the create-account controller, so the flow and its key survive rotation (spec 2026-10-17 §3).
 * The view model lives in the activity's store (login is not a nav route, §11), so leaving the flow or
 * finishing it [discard]s the controller: the next visit starts over with a new key.
 */
@HiltViewModel
class CreateAccountViewModel @Inject constructor(
    private val session: NostrSession,
    private val relayStore: RelayStore,
) : ViewModel() {
    var controller by mutableStateOf(newController())
        private set

    private fun newController() = CreateAccountController(
        actions = object : CreateAccountActions {
            override suspend fun create(key: NewKey, setup: PendingSetup) = session.createAccount(key, setup)
        },
        relays = { relayStore.relays.first() },
        dmRelays = PrefsDmSettingsStore.DEFAULT_RELAYS,
    )

    fun finish() = viewModelScope.launch {
        controller.finish()
        if (session.state.value is SessionState.Active) discard()
    }

    /** Drops the key; the flow starts over with a new one. */
    fun discard() {
        controller = newController()
    }
}

@Composable
fun CreateAccountScreen(onLeave: () -> Unit, vm: CreateAccountViewModel = hiltViewModel()) {
    val controller = vm.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val leave = { vm.discard(); onLeave() }
    BackHandler { if (!state.busy && !controller.back()) leave() }
    if (state.confirmCancel) {
        AlertDialog(
            onDismissRequest = controller::dismissCancel,
            text = { Text(stringResource(R.string.create_cancel_text)) },
            confirmButton = { TextButton(onClick = leave, modifier = Modifier.testTag("create-cancel-yes")) { Text(stringResource(R.string.create_cancel_yes)) } },
            dismissButton = { TextButton(onClick = controller::dismissCancel) { Text(stringResource(R.string.create_cancel_no)) } },
        )
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        when (state.step) {
            CreateStep.Welcome -> Scrolling("create-welcome") {
                Title(R.string.create_title)
                Text(stringResource(R.string.create_welcome_text), style = MaterialTheme.typography.bodyLarge)
            }
            CreateStep.Backup -> Scrolling("create-backup") { Backup(state, controller) }
            CreateStep.Name -> Scrolling("create-name-step") {
                Title(R.string.create_title)
                OutlinedTextField(
                    value = state.name, onValueChange = controller::setName, singleLine = true,
                    label = { Text(stringResource(R.string.create_name_label)) },
                    isError = state.name.trim().length > CreateAccountController.MAX_NAME,
                    supportingText = {
                        val tooLong = state.name.trim().length > CreateAccountController.MAX_NAME
                        Text(stringResource(if (tooLong) R.string.create_name_too_long else R.string.create_name_hint))
                    },
                    modifier = Modifier.fillMaxWidth().testTag("create-name"),
                )
            }
            CreateStep.Follow -> Follow(state, controller)
        }
        Spacer(Modifier.height(16.dp))
        if (state.step == CreateStep.Follow) {
            state.error?.let {
                Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = { vm.finish() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("create-finish")) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.create_finish))
            }
        } else {
            Button(onClick = controller::next, enabled = state.canNext, modifier = Modifier.fillMaxWidth().testTag("create-next")) {
                Text(stringResource(R.string.create_next))
            }
        }
    }
}

/** The step's content scrolls; the button below it stays in place. */
@Composable
private fun ColumnScope.Scrolling(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun Title(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.headlineSmall)
}

/** Step 2 (spec 2026-10-17 §3/§6): nsec and npub, the copy button and the tick; no screenshots meanwhile. */
@Composable
private fun Backup(state: CreateAccountState, controller: CreateAccountController) {
    SecureWindow()
    val context = LocalContext.current
    var copiedAt by remember { mutableStateOf<Long?>(null) }
    Title(R.string.create_backup_title)
    Text(state.nsec, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("create-nsec"))
    OutlinedButton(
        onClick = {
            copySecret(context, controller.copyText(), NSEC_CLIP_LABEL)
            copiedAt = System.currentTimeMillis()
        },
        modifier = Modifier.fillMaxWidth().testTag("create-copy"),
    ) { Text(stringResource(R.string.create_copy)) }
    copiedAt?.let { at ->
        Text(stringResource(R.string.create_copied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LaunchedEffect(at) { delay(SeedClipboardClear.CLEAR_AFTER_MS); copiedAt = null } // only hides the hint
    }
    Text(stringResource(R.string.create_backup_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Column {
        Text(stringResource(R.string.create_npub_label), style = MaterialTheme.typography.labelLarge)
        Text(state.npub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("create-npub"))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.saved, onCheckedChange = controller::setSaved, modifier = Modifier.testTag("create-saved"))
        Text(stringResource(R.string.create_saved), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Step 4 (spec 2026-10-17 §3/§11): the fixed list with names and a "who" line, all ticked; nothing is fetched. */
@Composable
private fun ColumnScope.Follow(state: CreateAccountState, controller: CreateAccountController) {
    Title(R.string.create_follow_title)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.create_follow_text), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("create-follows")) {
        items(SuggestedFollows.all, key = { it.pubkey }) { f ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = f.pubkey !in state.unticked,
                    onCheckedChange = { controller.toggle(f.pubkey) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("create-follow-" + f.pubkey.take(8)),
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(f.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(f.who), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
