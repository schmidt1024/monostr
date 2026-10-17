package com.monostr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NewKey
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.SecureWindow
import com.monostr.app.ui.common.copySecret
import com.monostr.app.ui.monero.NSEC_CLIP_LABEL
import com.monostr.app.ui.monero.SeedClipboardClear
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What "Show key" has: still loading, the key, or none (Amber, missing or unreadable key → the screen closes). */
sealed interface ShownKey {
    data object Loading : ShownKey
    class Key(val key: NewKey) : ShownKey
    data object None : ShownKey
}

/**
 * The one-shot pass from the Settings gate (warning, screen lock) to the key screen (spec 2026-10-17 §6).
 * In memory only: a route that Navigation restores after process death finds no grant and closes unseen.
 */
object ShowKeyGate {
    @Volatile private var granted = false

    fun grant() { granted = true }

    /** True once per [grant]. */
    @Synchronized fun consume(): Boolean = granted.also { granted = false }
}

/** Loads the key once, only with a [ShowKeyGate] grant, and keeps it in memory only, never in saved state (spec 2026-10-17 §6). */
@HiltViewModel
class ShowKeyViewModel @Inject constructor(session: NostrSession) : ViewModel() {
    private val _key = MutableStateFlow<ShownKey>(ShownKey.Loading)
    val key: StateFlow<ShownKey> = _key.asStateFlow()

    init {
        if (!ShowKeyGate.consume()) _key.value = ShownKey.None
        else viewModelScope.launch { _key.value = session.ownKey()?.let { ShownKey.Key(it) } ?: ShownKey.None }
    }
}

/** Settings → "Show key" (spec 2026-10-17 §6/§11): a full screen, so FLAG_SECURE covers it; nsec, copy, npub. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowKeyScreen(onBack: () -> Unit, vm: ShowKeyViewModel = hiltViewModel()) {
    SecureWindow()
    val shown by vm.key.collectAsStateWithLifecycle()
    LaunchedEffect(shown) { if (shown is ShownKey.None) onBack() }
    // leaving the app closes the screen: coming back (or a restore) needs the gate again
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onBack() }
    val context = LocalContext.current
    var copiedAt by remember { mutableStateOf<Long?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_show_key_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val key = (shown as? ShownKey.Key)?.key ?: return@Scaffold // loading, or about to close
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(key.nsec, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("show-key-nsec"))
            OutlinedButton(
                onClick = {
                    copySecret(context, key.nsec, NSEC_CLIP_LABEL)
                    copiedAt = System.currentTimeMillis()
                },
                modifier = Modifier.fillMaxWidth().testTag("show-key-copy"),
            ) { Text(stringResource(R.string.create_copy)) }
            copiedAt?.let { at ->
                Text(stringResource(R.string.create_copied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LaunchedEffect(at) { delay(SeedClipboardClear.CLEAR_AFTER_MS); copiedAt = null } // only hides the hint
            }
            Text(stringResource(R.string.create_backup_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Column {
                Text(stringResource(R.string.create_npub_label), style = MaterialTheme.typography.labelLarge)
                Text(key.npub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("show-key-npub"))
            }
        }
    }
}
