package com.monostr.app.ui.settings

import com.monostr.app.net.SendGate
import com.monostr.app.ui.common.MonostrSnackbarHost
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
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.AboutNote
import com.monostr.nostr.repo.MuteRepository
import rust.nostr.sdk.EventId
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

/** A muted conversation (backlog 81): its root id and, when the note is stored on the device, the first line of its text, its author and that author's picture (null: the generic avatar). */
data class MutedThreadUi(val rootId: String, val snippet: String?, val author: String? = null, val picture: String? = null)

/** What the device knows of a muted conversation's root note: the first line of its text and its author's pubkey. */
data class MutedRoot(val snippet: String?, val author: String)

/** [entries] are the muted accounts with their profiles; [readOnly] when the private part of the list cannot be read, so nothing can be changed (spec 9.1). */
data class MutedUiState(
    val entries: List<Pair<String, Profile>> = emptyList(),
    val threads: List<MutedThreadUi> = emptyList(),
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
    /** Whether anything may leave now; open only in tests. */
    private val sendGate: SendGate = SendGate.OPEN,
    /** The stored root notes of the muted conversations by id (database only, never a relay); ids not stored are left out. */
    private val roots: suspend (Set<String>) -> Map<String, MutedRoot> = { emptyMap() },
) {
    private val actions = MuteActions(mute, scope, profiles, background = muteScope, sendGate = sendGate)
    private val _state = MutableStateFlow(MutedUiState())
    val state: StateFlow<MutedUiState> = _state.asStateFlow()

    fun start() {
        scope.launch { profiles.updates.collect { byKey -> _state.update { s -> s.copy(entries = s.entries.map { (pk, p) -> pk to (byKey[pk] ?: p) }, threads = s.threads.map { t -> byKey[t.author]?.let { t.copy(picture = it.picture) } ?: t }) } } }
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
            combine(mute.mutedNewestFirst, mute.state, mute.mutedThreadsNewestFirst) { muted, list, threads -> Quad(muted, list.loaded && !list.writable, list.loaded || muted.isNotEmpty() || threads.isNotEmpty(), threads) }.collectLatest { (muted, readOnly, loaded, threads) ->
                // rows at once (known profiles stay, the rest shows the short pubkey); the profiles fill in below
                _state.update { s -> s.copy(threads = threads.map { id -> s.threads.firstOrNull { t -> t.rootId == id } ?: MutedThreadUi(id, null) }, readOnly = readOnly, loaded = loaded, entries = muted.map { pk -> pk to (s.entries.firstOrNull { it.first == pk }?.second ?: Profile.empty(pk)) }) }
                // the conversations' first lines and authors, from the stored notes and profiles only; a root not stored keeps the short id and the generic avatar
                if (threads.isNotEmpty()) {
                    val found = try {
                        roots(threads.toSet())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    val pictures = try {
                        profiles.local(found.values.map { it.author }.distinct()).associate { it.pubkey to it.picture }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    _state.update { s -> s.copy(threads = s.threads.map { t -> found[t.rootId]?.let { r -> t.copy(snippet = r.snippet ?: t.snippet, author = r.author, picture = pictures[r.author] ?: t.picture) } ?: t }) }
                }
                // best-effort like the reads below: the rows are already there
                try {
                    profiles.prefetch(muted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the rows keep the short pubkey
                }
                val byKey = try {
                    profiles.getAll(muted, MentionNames.LOCAL_MAX_AGE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyMap()
                }
                // inside the update: a profile that landed meanwhile stays
                _state.update { s -> s.copy(entries = muted.map { pk -> pk to (byKey[pk]?.takeUnless { it == Profile.empty(pk) } ?: s.entries.firstOrNull { it.first == pk }?.second ?: Profile.empty(pk)) }) }
            }
        }
    }

    /** Backlog 81: the conversation's row goes at once; a failure brings it back with the usual text. */
    fun unmuteThread(rootId: String) {
        actions.unmuteThread(rootId) { m -> if (m != null) _state.update { it.copy(message = m) } }
    }

    /** The row goes at once (the repository flips the set); a failure brings it back with the usual text. */
    fun unmute(pubkey: String) {
        actions.unmute(pubkey) { m -> if (m != null) _state.update { it.copy(message = m) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@HiltViewModel
class MutedViewModel @Inject constructor(session: NostrSession) : ViewModel() {
    private val ready = session.requireReady()
    val controller = MutedController(
        ready.mute, ready.profiles, viewModelScope, muteScope = ready.engine.backgroundScope, sendGate = session.requireReady().sendGate,
        roots = { ids -> ready.notifications.localNotes(ids).mapNotNull { (id, a) -> (a as? AboutNote.Found)?.let { f -> id to MutedRoot(firstLine(f.note.displayContent), f.note.author) } }.toMap() },
    )
    init { controller.start() }
}

/** One list per [threads] mode: the muted conversations (backlog 81) or the muted accounts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MutedScreen(threads: Boolean, onBack: () -> Unit, onOpenProfile: (String) -> Unit, vm: MutedViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.asString()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.controller.clearMessage() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (threads) R.string.settings_muted_threads else R.string.mute_settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { MonostrSnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("muted-list")) {
            if (state.readOnly) {
                item {
                    Text(
                        stringResource(R.string.mute_read_only), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.l).testTag("muted-read-only"),
                    )
                }
            }
            if (state.loaded && (if (threads) state.threads.isEmpty() else state.entries.isEmpty())) {
                item {
                    Text(
                        stringResource(if (threads) R.string.mute_settings_empty_threads else R.string.mute_settings_empty_accounts),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.l).testTag("muted-empty"),
                    )
                }
            }
            if (threads) {
                items(state.threads, key = { "thread-" + it.rootId }) { t ->
                    ListItem(
                        leadingContent = { Avatar(t.picture) },
                        headlineContent = { Text(t.snippet ?: shortNoteId(t.rootId), maxLines = 2) },
                        trailingContent = {
                            TextButton(onClick = { vm.controller.unmuteThread(t.rootId) }, enabled = !state.readOnly, modifier = Modifier.testTag("muted-thread-unmute-${t.rootId.take(8)}")) {
                                Text(stringResource(R.string.unmute_account))
                            }
                        },
                        modifier = Modifier.testTag("muted-thread-${t.rootId.take(8)}"),
                    )
                }
            } else {
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
}

/** `note1…` of the root id, shortened like the short npub; the id itself when it does not parse. */
internal fun shortNoteId(id: String): String = runCatching { EventId.parse(id).toBech32() }.map { it.take(9) + "…" + it.takeLast(4) }.getOrDefault(id.take(12))

/** The first non-blank line of [text], at most 80 characters (an ellipsis when cut); null when there is none. */
internal fun firstLine(text: String): String? {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    return if (line.length <= 80) line else line.take(79).trimEnd() + "…"
}
