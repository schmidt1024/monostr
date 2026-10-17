package com.monostr.app.ui.monero

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import com.monostr.app.ui.common.SecureWindow
import com.monostr.nostr.Npub
import com.monostr.tips.NetworkTag
import com.monostr.tips.ProtocolVersion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.monostr.app.BuildConfig
import com.monostr.app.R
import com.monostr.app.data.SecretStore
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.QrCode
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.theme.MonostrFonts
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MoneroSetupViewModel @Inject constructor(session: NostrSession, settings: TipSettingsStore, secrets: SecretStore, handle: SavedStateHandle) : ViewModel() {
    val controller: MoneroSetupController
    init {
        val ready = session.requireReady()
        controller = MoneroSetupController(
            intro = handle.get<Boolean>("intro") ?: false,
            gateway = ready.watchers, tips = ready.tips, settings = settings, secrets = secrets,
            relays = { session.ensureRelays(it) }, scope = viewModelScope, selfPubkey = ready.pubkey,
            // spec §12: where the wait of the first /v1/info after login comes from
            log = { if (BuildConfig.DEBUG) Log.d("monostr.setup", it) },
        )
        controller.start()
    }

    override fun onCleared() = controller.clear()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneroSetupScreen(onDone: () -> Unit, vm: MoneroSetupViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val c = vm.controller
    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (state.intro) R.string.setup_title_intro else R.string.setup_title)) },
            navigationIcon = { if (!state.intro) IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            // spec 6.1: leaving the intro with the system back gesture counts as "later"
            if (state.intro) BackHandler { c.skip() }
            when (val step = state.step) {
                is SetupStep.Watcher -> WatcherStep(step, state.intro, state.error?.asString(), c::setWatcherUrl, c::loadWatcher, c::cancelCheck, c::startPersonal, c::skip)
                is SetupStep.Choose -> ChooseStep(step, state.announced, c)
                is SetupStep.Seed -> SeedStep(step, c)
                is SetupStep.Verify -> VerifyStep(step, c)
                is SetupStep.Existing -> ExistingStep(step, state.error?.asString(), c)
                is SetupStep.Confirm -> ConfirmStep(step, state.error?.asString(), c)
                SetupStep.Registering -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text(stringResource(R.string.setup_registering)) }
                is SetupStep.Done -> DoneStep(step, c)
                is SetupStep.Personal -> PersonalStep(step, state.error?.asString(), c::setPersonalInput, c::setPersonalRelay, c::loadPersonal, c::backToWatcher, c::cancelCheck)
                is SetupStep.PersonalConfirm -> PersonalConfirmStep(step, state.error?.asString(), c::confirmPersonal, c::backToWatcher)
            }
        }
    }
}

/** Spec §12: a running check shows a thin bar, a spinner in the button and, after 3 s, a hint with Cancel. */
@Composable
private fun CheckProgress(slow: Boolean, onCancel: (() -> Unit)?, progressTag: String, hintTag: String, cancelTag: String) {
    LinearProgressIndicator(Modifier.fillMaxWidth().testTag(progressTag))
    if (slow) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.setup_slow_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag(hintTag))
            if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.testTag(cancelTag)) { Text(stringResource(R.string.setup_cancel_check)) }
        }
    }
}

/** The button content while busy: a small spinner and [text]. */
@Composable
private fun BusyLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

@Composable
internal fun ColumnScope.WatcherStep(
    step: SetupStep.Watcher, intro: Boolean, error: String?,
    onUrl: (String) -> Unit, onLoad: () -> Unit, onCancel: () -> Unit, onPersonal: () -> Unit, onSkip: () -> Unit,
) {
    if (intro) {
        Text(stringResource(R.string.setup_intro_text), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.setup_intro_wallet_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
    Text(stringResource(R.string.setup_watcher_heading), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_watcher_text), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = step.url, onValueChange = onUrl, singleLine = true, label = { Text(stringResource(R.string.setup_watcher_url)) },
        isError = error != null, supportingText = { error?.let { Text(it) } }, enabled = !step.loading,
        modifier = Modifier.fillMaxWidth().testTag("setup-watcher-url"),
    )
    // the start phase (stored settings, announced address) shows progress too, but "Loading…" and no Cancel
    if (step.loading) CheckProgress(step.slow, onCancel.takeIf { step.checking }, "setup-progress", "setup-slow-hint", "setup-cancel-check")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onLoad, enabled = !step.loading, modifier = Modifier.fillMaxWidth().testTag("setup-load")) {
        when {
            step.checking -> BusyLabel(stringResource(R.string.setup_checking))
            step.loading -> BusyLabel(stringResource(R.string.state_loading))
            else -> Text(stringResource(if (step.retry) R.string.setup_try_again else R.string.setup_load_watcher))
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onPersonal, enabled = !step.loading, modifier = Modifier.fillMaxWidth().testTag("setup-personal")) { Text(stringResource(R.string.setup_personal_watcher)) }
    Text(stringResource(R.string.setup_personal_watcher_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (intro) TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End).testTag("setup-skip")) { Text(stringResource(R.string.setup_skip)) }
}

@Composable
internal fun PersonalStep(step: SetupStep.Personal, error: String?, onInput: (String) -> Unit, onRelay: (String) -> Unit, onLoad: () -> Unit, onBack: () -> Unit, onCancel: () -> Unit = {}) {
    Text(stringResource(R.string.setup_personal_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_personal_text), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = step.input, onValueChange = onInput, singleLine = true, label = { Text(stringResource(R.string.setup_personal_input_label)) },
        isError = error != null, supportingText = { error?.let { Text(it) } }, enabled = !step.loading,
        modifier = Modifier.fillMaxWidth().testTag("setup-personal-input"),
    )
    OutlinedTextField(
        value = step.relay, onValueChange = onRelay, singleLine = true, label = { Text(stringResource(R.string.setup_personal_relay_label)) },
        placeholder = { Text(stringResource(R.string.setup_personal_relay_hint)) }, enabled = !step.loading,
        modifier = Modifier.fillMaxWidth().testTag("setup-personal-relay"),
    )
    if (step.loading) CheckProgress(step.slow, onCancel, "setup-personal-progress", "setup-personal-slow-hint", "setup-personal-cancel-check")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onLoad, enabled = !step.loading, modifier = Modifier.fillMaxWidth().testTag("setup-personal-load")) {
        if (step.loading) BusyLabel(stringResource(R.string.setup_checking))
        else Text(stringResource(if (step.retry) R.string.setup_try_again else R.string.setup_personal_load))
    }
    TextButton(onClick = onBack, enabled = !step.loading, modifier = Modifier.testTag("setup-personal-back")) { Text(stringResource(R.string.action_back)) }
}

@Composable
internal fun PersonalConfirmStep(step: SetupStep.PersonalConfirm, error: String?, onConfirm: () -> Unit, onBack: () -> Unit) {
    val status = step.status
    val address = status.address.encode()
    val height = if (status.height > 0) status.height.toString() else stringResource(R.string.setup_personal_height_unknown)
    Text(
        stringResource(R.string.setup_personal_info, Npub.short(status.watcherPubkey), NetworkTag.toTag(status.network!!), height, address.take(8), address.takeLast(6)),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("setup-personal-info"),
    )
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_personal_privacy), style = MaterialTheme.typography.bodyMedium)
    if (ProtocolVersion.supportsHiddenAmounts(status.protocol)) AmountsPrivateText()
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().testTag("setup-personal-confirm")) { Text(stringResource(R.string.setup_personal_confirm)) }
    TextButton(onClick = onBack, modifier = Modifier.testTag("setup-personal-back")) { Text(stringResource(R.string.action_back)) }
}

@Composable
private fun ChooseStep(step: SetupStep.Choose, announced: String?, c: MoneroSetupController) {
    Text(stringResource(R.string.setup_watcher_info, Npub.short(step.info.pubkey), step.info.network, step.info.height), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    if (announced != null) {
        Text(stringResource(R.string.setup_announced_warning, announced.take(8), announced.takeLast(6)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("setup-announced"))
        Spacer(Modifier.height(16.dp))
    }
    Text(stringResource(R.string.setup_privacy), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.setup_watcher_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Button(onClick = c::startNew, modifier = Modifier.fillMaxWidth().testTag("setup-new")) { Text(stringResource(R.string.setup_new_wallet)) }
    Text(stringResource(R.string.setup_path_new_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = c::startExisting, modifier = Modifier.fillMaxWidth().testTag("setup-existing")) { Text(stringResource(R.string.setup_existing_wallet)) }
    Text(stringResource(R.string.setup_path_existing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (step.canReuse) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = c::reuseStored, modifier = Modifier.fillMaxWidth().testTag("setup-reuse")) { Text(stringResource(R.string.setup_reuse_wallet)) }
    }
}

@Composable
private fun SeedStep(step: SetupStep.Seed, c: MoneroSetupController) {
    SecureWindow()
    Text(stringResource(R.string.setup_seed_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_seed_text), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    step.words.chunked(3).forEachIndexed { row, words ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            words.forEachIndexed { col, w -> Text("${row * 3 + col + 1}. $w", fontFamily = MonostrFonts.mono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.setup_restore_height, step.restoreHeight), fontFamily = MonostrFonts.mono)
    SeedClipboard(step.words.joinToString(" "))
    Spacer(Modifier.height(16.dp))
    Button(onClick = c::seedWritten, modifier = Modifier.fillMaxWidth().testTag("setup-seed-written")) { Text(stringResource(R.string.setup_seed_written)) }
}

@Composable
private fun VerifyStep(step: SetupStep.Verify, c: MoneroSetupController) {
    SecureWindow()
    val answers = remember(step.positions) { mutableStateOf(List(step.positions.size) { "" }) }
    Text(stringResource(R.string.setup_verify_title), style = MaterialTheme.typography.titleMedium)
    step.positions.forEachIndexed { i, pos ->
        OutlinedTextField(
            value = answers.value[i], onValueChange = { v -> answers.value = answers.value.toMutableList().also { it[i] = v } },
            singleLine = true, label = { Text(stringResource(R.string.setup_verify_word, pos + 1)) }, isError = step.wrong,
            modifier = Modifier.fillMaxWidth().testTag("setup-word-$i"),
        )
    }
    if (step.wrong) Text(stringResource(R.string.setup_verify_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    Button(onClick = { c.verify(answers.value) }, enabled = answers.value.all { it.isNotBlank() }, modifier = Modifier.fillMaxWidth().testTag("setup-verify")) { Text(stringResource(R.string.action_check)) }
    TextButton(onClick = c::backToSeed) { Text(stringResource(R.string.setup_back_to_seed)) }
}

@Composable
private fun ExistingStep(step: SetupStep.Existing, error: String?, c: MoneroSetupController) {
    Text(stringResource(R.string.setup_existing_title), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = step.address, onValueChange = c::setAddress, label = { Text(stringResource(R.string.setup_address_label)) }, isError = error != null, modifier = Modifier.fillMaxWidth().testTag("setup-address"))
    OutlinedTextField(
        value = step.viewKey, onValueChange = c::setViewKey, singleLine = true, label = { Text(stringResource(R.string.setup_view_key_label)) },
        visualTransformation = PasswordVisualTransformation(), isError = error != null, supportingText = { error?.let { Text(it) } },
        modifier = Modifier.fillMaxWidth().testTag("setup-view-key"),
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = c::checkExisting, enabled = step.address.isNotBlank() && step.viewKey.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("setup-check")) { Text(stringResource(R.string.action_check)) }
}

@Composable
private fun ConfirmStep(step: SetupStep.Confirm, error: String?, c: MoneroSetupController) {
    var understood by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.setup_confirm_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_confirm_text), style = MaterialTheme.typography.bodyMedium)
    if (step.amountsHidden) AmountsPrivateText()
    Spacer(Modifier.height(8.dp))
    Text(step.address, fontFamily = MonostrFonts.mono, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = understood, onCheckedChange = { understood = it }, modifier = Modifier.testTag("setup-understood"))
        Text(stringResource(R.string.setup_understood))
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(12.dp))
    Button(onClick = c::confirm, enabled = understood, modifier = Modifier.fillMaxWidth().testTag("setup-confirm")) { Text(stringResource(R.string.setup_register)) }
}

/** Protocol 0.5: the new payment info hides tip amounts. */
@Composable
private fun AmountsPrivateText() {
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.setup_amounts_private), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("setup-amounts-private"))
}

@Composable
private fun ColumnScope.DoneStep(step: SetupStep.Done, c: MoneroSetupController) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        // Android 13+: tips received now should be notified; asked once, right after the setup
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Text(stringResource(R.string.setup_done_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_done_text), style = MaterialTheme.typography.bodyMedium)
    if (step.amountsHidden) AmountsPrivateText()
    Spacer(Modifier.height(12.dp))
    QrCode(step.address, Modifier.size(220.dp).align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(8.dp))
    Text(step.address, fontFamily = MonostrFonts.mono, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(step.address)) }) { Text(stringResource(R.string.action_copy_address)) }
    Text(stringResource(R.string.settings_monero_wallet_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    step.restoreHeight?.let {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_done_import, it), style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = c::finish, modifier = Modifier.fillMaxWidth().testTag("setup-done")) { Text(stringResource(R.string.action_done)) }
}
