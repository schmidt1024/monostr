package com.monostr.app.ui.monero

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.monostr.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

/** Opt-in seed copy: a warning dialog first; [onCopy] gets the words only when the user confirms. */
@Composable
fun SeedCopyButton(words: String, onCopy: (String) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("seed-copy")) { Text(stringResource(R.string.seed_copy)) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.seed_copy_warning_title)) },
            text = { Text(stringResource(R.string.seed_copy_warning_text)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onCopy(words) }, modifier = Modifier.testTag("seed-copy-confirm")) { Text(stringResource(R.string.seed_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("seed-copy-cancel")) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/**
 * Clipboard side of the seed copy: the clip is flagged sensitive (Android 13+ hides the preview and
 * keeps it out of the clipboard history) and cleared again after [SEED_CLEAR_AFTER_MS] if it is still ours.
 */
@SuppressLint("InlinedApi") // EXTRA_IS_SENSITIVE is a compile-time constant; older Androids ignore the flag
@Composable
fun SeedClipboard(words: String) {
    val context = LocalContext.current
    var copiedAt by remember { mutableStateOf<Long?>(null) }
    SeedCopyButton(words) { text ->
        val clip = ClipData.newPlainText(SEED_CLIP_LABEL, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        // the clear must not die with this composable: the user leaves the step or the app to paste
        SeedClipboardClear.schedule(clipboardScope, AndroidClipboardPort(context))
        copiedAt = System.currentTimeMillis()
    }
    copiedAt?.let { at ->
        Text(stringResource(R.string.seed_copied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LaunchedEffect(at) { delay(SeedClipboardClear.CLEAR_AFTER_MS); copiedAt = null } // only hides the hint
    }
}

/** Process-wide scope for the clipboard timer; the main dispatcher because the clipboard manager is a main-thread service. */
private val clipboardScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
