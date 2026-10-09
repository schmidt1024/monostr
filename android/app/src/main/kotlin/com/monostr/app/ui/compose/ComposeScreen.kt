package com.monostr.app.ui.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.media.ImageTarget
import com.monostr.app.media.MediaUploader
import com.monostr.app.media.UploadedMedia
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.QuoteCard
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.uploadMessage
import com.monostr.app.ui.common.userMessage
import com.monostr.nostr.Npub
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.ThreadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One picture attached to the note being written (spec 5.2): uploading, uploaded, or failed. */
data class Attachment(
    val id: Long,
    /** The picked picture as a `content://` URI; the tile shows it, a retry reads it again. */
    val source: String,
    val progress: Float = 0f,
    val uploaded: UploadedMedia? = null,
    val error: UiText? = null,
) {
    val uploading: Boolean get() = uploaded == null && error == null
}

data class ComposeUiState(
    val replyTo: Note? = null,
    val sending: Boolean = false,
    val done: Boolean = false,
    val error: UiText? = null,
    val replyUnresolved: Boolean = false,
    val pendingResend: Boolean = false,
    /** Spec 4.1: the note being quoted, once loaded. */
    val quoted: Note? = null,
    val quoteUnresolved: Boolean = false,
    /** Spec 4.2: at most eight candidates for the `@partial` before the cursor. */
    val suggestions: List<Profile> = emptyList(),
    /** pubkey → name of the people picked so far; the text field shows their tokens as `@name`. */
    val mentionNames: Map<String, String> = emptyMap(),
    /** Spec 5.2 (Plan 10e): the pictures of this note, in the order they will appear. */
    val attachments: List<Attachment> = emptyList(),
    /** Spec 5.2: the note gets a content warning; only meaningful with pictures. */
    val sensitive: Boolean = false,
) {
    /** Every picture is on the server: nothing uploading, nothing failed. */
    val attachmentsReady: Boolean get() = attachments.all { it.uploaded != null }
    /** Room for another picture, and the note is not frozen. */
    val canAttach: Boolean get() = attachments.size < NoteMedia.MAX && !sending && !pendingResend
}

class ComposeController(
    private val replyToId: String?,
    private val threads: ThreadRepository,
    private val publish: PublishRepository,
    private val scope: CoroutineScope,
    private val quoteId: String? = null,
    private val suggester: MentionSuggester? = null,
    private val suggestDelayMs: Long = 150,
    /** Null in tests that do not deal with pictures: then nothing can be attached. */
    private val uploader: MediaUploader? = null,
) {
    private val _state = MutableStateFlow(ComposeUiState())
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    /** The event stored by an offline send; while set, [send] re-sends it instead of signing a new note. */
    private var pendingEventId: String? = null
    private var suggestJob: Job? = null
    private var nextAttachmentId = 0L
    private val uploads = HashMap<Long, Job>()
    /**
     * True from the tap on Send until the send ends with an exception: while it is in flight, or once
     * it produced an event (sent, or stored for a resend), the pictures may be named by that event and must stay.
     */
    private var consumed = false

    fun start() {
        suggester?.let { s ->
            scope.launch {
                try {
                    s.preload()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // no follows: the local name search alone still suggests
                }
            }
        }
        if (replyToId != null) scope.launch {
            try {
                threads.observe(replyToId).firstOrNull()?.let { v -> _state.update { it.copy(replyTo = v.focused) } }
                if (_state.value.replyTo == null) {
                    _state.update { it.copy(replyUnresolved = true, error = uiText(R.string.compose_error_reply_target)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(replyUnresolved = true, error = uiText(R.string.compose_error_reply_target)) }
            }
        }
        if (quoteId != null) scope.launch {
            val quoted = try {
                threads.note(quoteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // a send tapped while the quote was loading left quote_missing behind; the loaded quote clears it
            _state.update { if (quoted != null) it.copy(quoted = quoted, error = null) else it.copy(quoteUnresolved = true, error = uiText(R.string.quote_missing)) }
        }
    }

    fun onTextChange(text: String, cursor: Int) {
        val s = suggester ?: return
        suggestJob?.cancel()
        val active = MentionQuery.active(text, cursor)
        if (active == null) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = scope.launch {
            delay(suggestDelayMs) // the local name search reads every stored kind 0; wait for a typing pause
            val found = try {
                s.suggest(active.query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(suggestions = found) }
        }
    }

    /** Replaces the `@partial` before [cursor] by the picked person's `nostr:npub`; null when no mention is being typed. */
    fun pick(text: String, cursor: Int, profile: Profile): MentionQuery.Edit? {
        val active = MentionQuery.active(text, cursor) ?: return null
        suggestJob?.cancel()
        _state.update { it.copy(suggestions = emptyList(), mentionNames = it.mentionNames + (profile.pubkey to profile.shownName)) }
        return MentionQuery.replace(text, active, cursor, profile.pubkey)
    }

    /** Spec 5.2: the picked pictures join the note, as far as there is room, and start uploading at once. */
    fun attach(sources: List<String>) {
        val up = uploader ?: return
        if (_state.value.pendingResend || _state.value.sending) return
        val free = (NoteMedia.MAX - _state.value.attachments.size).coerceAtLeast(0)
        // a picture already on the note, or twice in the pick, is taken once
        val known = _state.value.attachments.mapTo(HashSet()) { it.source }
        for (source in sources.filter { known.add(it) }.take(free)) {
            val id = nextAttachmentId++
            _state.update { it.copy(attachments = it.attachments + Attachment(id, source), error = null) }
            startUpload(up, id, source)
        }
    }

    private fun startUpload(up: MediaUploader, id: Long, source: String) {
        uploads[id] = scope.launch {
            try {
                val media = up.upload(source, ImageTarget.NOTE) { p -> patch(id) { it.copy(progress = p) } }
                patch(id) { it.copy(uploaded = media, progress = 1f) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                patch(id) { it.copy(error = e.uploadMessage()) }
            }
        }
    }

    private fun patch(id: Long, change: (Attachment) -> Attachment) =
        _state.update { s -> s.copy(attachments = s.attachments.map { if (it.id == id) change(it) else it }) }

    /** Uploads a failed picture again. */
    fun retry(id: Long) {
        val up = uploader ?: return
        if (_state.value.sending) return
        val a = _state.value.attachments.firstOrNull { it.id == id } ?: return
        if (a.error == null) return
        patch(id) { it.copy(error = null, progress = 0f) }
        startUpload(up, id, a.source)
    }

    /** Takes a picture off the note; one that is already on the server is deleted there (spec 5.2). */
    fun removeAttachment(id: Long) {
        val a = _state.value.attachments.firstOrNull { it.id == id } ?: return
        if (_state.value.pendingResend || _state.value.sending) return
        uploads.remove(id)?.cancel()
        _state.update { s ->
            val left = s.attachments.filterNot { it.id == id }
            s.copy(attachments = left, sensitive = s.sensitive && left.isNotEmpty(), error = null)
        }
        // the same picture twice on the server is one blob: it goes with the last tile that names it
        val media = a.uploaded
        if (media != null && _state.value.attachments.none { it.uploaded?.sha256 == media.sha256 }) uploader?.discard(media)
    }

    fun setSensitive(on: Boolean) = _state.update {
        if (it.sending) it else it.copy(sensitive = on && it.attachments.isNotEmpty())
    }

    /**
     * The composer is being left. Pictures of a note that was never sent are deleted at the server
     * (spec 5.2); pictures of a note that went out, or waits for a resend, stay. Starts nothing on
     * [scope]: it is called from `onCleared`, where that scope is already cancelled.
     */
    fun discardUnsent() {
        if (consumed) return
        uploads.values.forEach { it.cancel() }
        uploads.clear()
        _state.value.attachments.mapNotNull { it.uploaded }.distinctBy { it.sha256 }.forEach { uploader?.discard(it) }
    }

    fun send(text: String) {
        if (_state.value.sending) return
        val quoting = quoteId != null
        val attachments = _state.value.attachments
        if (attachments.any { it.uploaded == null }) {
            _state.update { it.copy(error = uiText(R.string.compose_error_uploads)) }
            return
        }
        if (text.isBlank() && !quoting && attachments.isEmpty()) {
            _state.update { it.copy(error = uiText(R.string.compose_error_empty)) }
            return
        }
        if (replyToId != null && _state.value.replyTo == null) {
            _state.update { it.copy(error = uiText(R.string.compose_error_reply_target)) }
            return
        }
        if (quoting && _state.value.quoted == null) {
            _state.update { it.copy(error = uiText(R.string.quote_missing)) }
            return
        }
        // synchronously, before the launch: the pictures are frozen and protected from this tap on
        val firstSend = pendingEventId == null
        consumed = true
        _state.update { it.copy(sending = true, error = null) }
        scope.launch {
            try {
                val parent = _state.value.replyTo
                val quoted = _state.value.quoted
                val pending = pendingEventId
                val media = attachments.mapNotNull { it.uploaded?.toAttachment() }
                val sensitive = _state.value.sensitive
                val result = when {
                    pending != null -> publish.resend(pending)
                    parent != null -> publish.reply(text, parent, media, sensitive)
                    quoted != null -> publish.quote(text, quoted, media, sensitive)
                    else -> publish.post(text, media, sensitive)
                }
                pendingEventId = if (result.sentToAny) null else result.eventId
                _state.update {
                    it.copy(
                        sending = false, done = result.sentToAny, pendingResend = !result.sentToAny,
                        error = if (result.sentToAny) null else uiText(R.string.compose_notice_offline),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstSend) consumed = false // no event was produced: the pictures are unused again; a stored event still names them
                _state.update { it.copy(sending = false, error = e.userMessage()) }
            }
        }
    }
}

@HiltViewModel
class ComposeViewModel @Inject constructor(session: NostrSession, handle: SavedStateHandle) : ViewModel() {
    private val ready = session.requireReady()
    val quoteId: String? = handle["quote"]
    val controller = ComposeController(
        handle["replyTo"], ready.threads, ready.publish, viewModelScope, quoteId = quoteId,
        suggester = MentionSuggester(ready.feed, ready.profiles, ready.search, ready.pubkey),
        uploader = ready.media,
    )
    init { controller.start() }

    // lifecycle 2.11 cancels viewModelScope before this runs; discardUnsent starts nothing on it
    override fun onCleared() = controller.discardUnsent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(onDone: () -> Unit, vm: ComposeViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val transformation = remember(state.mentionNames) { MentionTransformation(state.mentionNames) }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    // the system photo picker needs no permission (spec 5.2); it hands back content:// URIs
    val onPicked: (List<Uri>) -> Unit = { uris -> if (uris.isNotEmpty()) vm.controller.attach(uris.map { it.toString() }) }
    // spec 5.2: the picker offers exactly the free places, so one launcher per possible count, all registered
    // at every composition (key: each its own saved registry key); the multi picker cannot be asked for one
    val pickMany = (2..NoteMedia.MAX).associateWith { n ->
        key(n) { rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(n), onPicked) }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onPicked(listOfNotNull(uri)) }
    val imagesOnly = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (state.replyTo != null) R.string.compose_title_reply else R.string.compose_title_new)) },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).imePadding().padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            state.replyTo?.let {
                Text(it.displayContent, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                Spacer(Modifier.height(12.dp))
            }
            // after an offline send the stored event is re-sent as is, so the text is frozen
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    // spec 8: a backspace or cut into a shown @name removes the whole mention
                    val cut = MentionQuery.cutToken(field.text, v.text, state.mentionNames)
                    field = if (cut != null) TextFieldValue(cut.text, TextRange(cut.cursor)) else v
                    vm.controller.onTextChange(field.text, field.selection.end)
                },
                readOnly = state.pendingResend,
                visualTransformation = transformation,
                modifier = Modifier.fillMaxWidth().height(200.dp).testTag("compose-text"),
                placeholder = { Text(stringResource(R.string.compose_placeholder)) },
                isError = state.error != null,
                supportingText = { state.error?.let { Text(it.asString()) } },
            )
            if (state.suggestions.isNotEmpty()) {
                // spec 4.2: right under the field, i.e. above the keyboard (imePadding on the column)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("mention-list")) {
                    items(state.suggestions, key = { it.pubkey }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.controller.pick(field.text, field.selection.end, p)?.let { e -> field = TextFieldValue(e.text, TextRange(e.cursor)) }
                            }.padding(vertical = 8.dp).testTag("mention-${p.pubkey.take(8)}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(p.picture, Modifier.size(32.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.shownName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(Npub.short(p.pubkey), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            vm.quoteId?.let { id ->
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.compose_quote_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                // spec 4.1: shown, not editable; taps on the card do nothing here
                Box(Modifier.testTag("compose-quote")) { QuoteCard(id, hints = emptyList(), names = emptyMap(), onOpenNote = {}, onOpenProfile = {}) }
            }
            if (state.attachments.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AttachmentTiles(
                    state.attachments, enabled = !state.sending && !state.pendingResend,
                    onRemove = vm.controller::removeAttachment, onRetry = vm.controller::retry,
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.compose_sensitive), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.compose_sensitive_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = state.sensitive, onCheckedChange = vm.controller::setSensitive,
                        enabled = !state.sending && !state.pendingResend, modifier = Modifier.testTag("compose-sensitive"),
                    )
                }
            }
            IconButton(
                onClick = {
                    val free = NoteMedia.MAX - state.attachments.size
                    val many = pickMany[free]
                    if (many != null) many.launch(imagesOnly) else pickOne.launch(imagesOnly)
                },
                enabled = state.canAttach,
                modifier = Modifier.testTag("compose-attach"),
            ) {
                Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.compose_attach))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.controller.send(field.text) },
                enabled = !state.sending && (field.text.isNotBlank() || vm.quoteId != null || state.attachments.isNotEmpty()) &&
                    state.attachmentsReady && !state.replyUnresolved && !state.quoteUnresolved,
                modifier = Modifier.fillMaxWidth().testTag("compose-send"),
            ) {
                Text(stringResource(when {
                    state.sending -> R.string.state_sending
                    state.pendingResend -> R.string.action_send_again
                    else -> R.string.action_send
                }))
            }
        }
    }
}
