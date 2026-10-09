package com.monostr.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.MentionNames
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.userMessage
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [entries] are the muted accounts with their profiles; [readOnly] when the private part of the list cannot be read, so nothing can be changed (spec 9.1). */
data class MutedUiState(
    val entries: List<Pair<String, Profile>> = emptyList(),
    val readOnly: Boolean = false,
    /** The list is known loaded, or something is muted: "Nobody is muted" waits for it, not for the first emission. */
    val loaded: Boolean = false,
    val message: UiText? = null,
)

/** The settings list of muted accounts (spec 9.5): follows [MuteRepository.muted], "Unmute" removes a row. */
class MutedController(
    private val mute: MuteRepository,
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope,
    /** The scope mute writes run on, so they survive the screen that started them (session: the engine's background scope); null (tests) = [scope]. */
    muteScope: CoroutineScope? = null,
) {
    private val actions = MuteActions(mute, scope, profiles, background = muteScope)
    private val _state = MutableStateFlow(MutedUiState())
    val state: StateFlow<MutedUiState> = _state.asStateFlow()

    fun start() {
        // here the signer may be asked (interactive): the user opened the list on purpose
        scope.launch {
            try {
                mute.ensureLoaded(interactive = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.userMessage()) }
            }
        }
        scope.launch {
            combine(mute.muted, mute.state) { muted, list -> Triple(muted, list.loaded && !list.writable, list.loaded || muted.isNotEmpty()) }.collectLatest { (muted, readOnly, loaded) ->
                // rows at once (known profiles stay, the rest shows the short pubkey); the profiles fill in below
                _state.update { s -> s.copy(readOnly = readOnly, loaded = loaded, entries = muted.map { pk -> pk to (s.entries.firstOrNull { it.first == pk }?.second ?: Profile.empty(pk)) }) }
                // best-effort like the reads below: the rows are already there
                try {
                    profiles.prefetch(muted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the rows keep the short pubkey
                }
                val entries = muted.map { pk ->
                    pk to try {
                        profiles.get(pk, MentionNames.LOCAL_MAX_AGE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Profile.empty(pk)
                    }
                }
                _state.update { it.copy(entries = entries) }
            }
        }
    }

    /** The row goes at once (the repository flips the set); a failure brings it back with the usual text. */
    fun unmute(pubkey: String) {
        actions.unmute(pubkey) { m -> if (m != null) _state.update { it.copy(message = m) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@HiltViewModel
class MutedViewModel @Inject constructor(session: NostrSession) : ViewModel() {
    private val ready = session.requireReady()
    val controller = MutedController(ready.mute, ready.profiles, viewModelScope, muteScope = ready.engine.backgroundScope)
    init { controller.start() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MutedScreen(onBack: () -> Unit, onOpenProfile: (String) -> Unit, vm: MutedViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.asString()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.controller.clearMessage() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mute_settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("muted-list")) {
            if (state.readOnly) {
                item {
                    Text(
                        stringResource(R.string.mute_read_only), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp).testTag("muted-read-only"),
                    )
                }
            }
            if (state.loaded && state.entries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.mute_settings_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp).testTag("muted-empty"),
                    )
                }
            }
            items(state.entries, key = { it.first }) { (pubkey, profile) ->
                ListItem(
                    leadingContent = { Avatar(profile.picture) },
                    headlineContent = { Text(profile.shownName) },
                    trailingContent = {
                        TextButton(onClick = { vm.controller.unmute(pubkey) }, enabled = !state.readOnly, modifier = Modifier.testTag("muted-unmute-${pubkey.take(8)}")) {
                            Text(stringResource(R.string.unmute_account))
                        }
                    },
                    modifier = Modifier.clickable { onOpenProfile(pubkey) },
                )
            }
        }
    }
}
