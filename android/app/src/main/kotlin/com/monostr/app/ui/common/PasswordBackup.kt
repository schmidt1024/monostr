package com.monostr.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.monero.NSEC_CLIP_LABEL
import com.monostr.app.ui.monero.SeedClipboardClear
import com.monostr.nostr.Nip49
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the password-backup dialog (spec 2026-10-29 ncryptsec, Export). Never prints the password or the backup. */
data class PasswordBackupState(
    val password: String = "",
    val repeat: String = "",
    val step: Step = Step.Edit,
    val ncryptsec: String? = null,
) {
    enum class Step { Edit, Busy, Done, Failed }

    val tooShort: Boolean get() = password.length < MIN_LENGTH
    val mismatch: Boolean get() = repeat.isNotEmpty() && repeat != password
    val canCreate: Boolean get() = !tooShort && password == repeat && (step == Step.Edit || step == Step.Failed)

    override fun toString(): String = "PasswordBackupState(step=$step)"

    companion object {
        const val MIN_LENGTH = 8
    }
}

/** Validation and states of the dialog, plain and JVM-testable. Memory only; the password is passed on unchanged. */
class PasswordBackupController(
    private val secretHex: String,
    private val encrypt: suspend (String, String) -> String = Nip49::encrypt,
) {
    private val _state = MutableStateFlow(PasswordBackupState())
    val state: StateFlow<PasswordBackupState> = _state.asStateFlow()

    fun setPassword(p: String) = _state.update { if (it.step == PasswordBackupState.Step.Busy) it else it.copy(password = p) }
    fun setRepeat(p: String) = _state.update { if (it.step == PasswordBackupState.Step.Busy) it else it.copy(repeat = p) }

    /** No-op unless [PasswordBackupState.canCreate]; a second call while busy does nothing. */
    suspend fun create() {
        var password: String? = null
        _state.update { s ->
            if (!s.canCreate) return@update s
            password = s.password
            s.copy(step = PasswordBackupState.Step.Busy)
        }
        val p = password ?: return
        try {
            val out = encrypt(secretHex, p)
            _state.update { it.copy(step = PasswordBackupState.Step.Done, ncryptsec = out) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { // the message could carry secrets; the user only sees a fixed text
            _state.update { it.copy(step = PasswordBackupState.Step.Failed, ncryptsec = null) }
        }
    }
}

/**
 * "Back up with a password" (spec 2026-10-29 ncryptsec, Export): password twice, then the ncryptsec with a copy button.
 * Plain `remember` only: closing the dialog (or the screen) cancels a running encrypt and drops everything.
 */
@Composable
fun PasswordBackupDialog(secretHex: String, onDismiss: () -> Unit) {
    val controller = remember(secretHex) { PasswordBackupController(secretHex) }
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copiedAt by remember { mutableStateOf<Long?>(null) }
    val step = state.step
    val editing = step == PasswordBackupState.Step.Edit || step == PasswordBackupState.Step.Failed
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    editing -> {
                        OutlinedTextField(
                            value = state.password, onValueChange = controller::setPassword, singleLine = true,
                            label = { Text(stringResource(R.string.backup_password_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = state.password.isNotEmpty() && state.tooShort,
                            supportingText = { if (state.password.isNotEmpty() && state.tooShort) Text(stringResource(R.string.backup_password_too_short)) },
                            modifier = Modifier.fillMaxWidth().testTag("backup-password"),
                        )
                        OutlinedTextField(
                            value = state.repeat, onValueChange = controller::setRepeat, singleLine = true,
                            label = { Text(stringResource(R.string.backup_password_repeat)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = state.mismatch,
                            supportingText = { if (state.mismatch) Text(stringResource(R.string.backup_password_mismatch)) },
                            modifier = Modifier.fillMaxWidth().testTag("backup-password-repeat"),
                        )
                        if (step == PasswordBackupState.Step.Failed) {
                            Text(stringResource(R.string.backup_password_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    step == PasswordBackupState.Step.Busy -> CircularProgressIndicator(Modifier.testTag("backup-busy"))
                    else -> {
                        Text(state.ncryptsec.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("backup-ncryptsec"))
                        OutlinedButton(
                            onClick = {
                                copySecret(context, state.ncryptsec.orEmpty(), NSEC_CLIP_LABEL)
                                copiedAt = System.currentTimeMillis()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("backup-ncryptsec-copy"),
                        ) { Text(stringResource(R.string.backup_copy)) }
                        copiedAt?.let { at ->
                            Text(stringResource(R.string.backup_copied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LaunchedEffect(at) { delay(SeedClipboardClear.CLEAR_AFTER_MS); copiedAt = null } // only hides the hint
                        }
                        Text(stringResource(R.string.backup_password_note), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            when {
                editing -> TextButton(
                    onClick = { scope.launch { controller.create() } },
                    enabled = state.canCreate,
                    modifier = Modifier.testTag("backup-password-create"),
                ) { Text(stringResource(R.string.backup_password_create)) }
                step == PasswordBackupState.Step.Done -> TextButton(onClick = onDismiss, modifier = Modifier.testTag("backup-close")) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = if (step == PasswordBackupState.Step.Done) null else {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
        },
    )
}
