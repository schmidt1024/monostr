package com.monostr.app.ui.compose

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.VideoLibrary
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.Draft
import com.monostr.app.data.DraftStore
import com.monostr.app.data.DraftTargets
import com.monostr.app.media.ImageTarget
import com.monostr.app.media.MediaUploader
import com.monostr.app.media.UploadedMedia
import com.monostr.app.media.UploadException
import com.monostr.app.media.VideoRules
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.QuoteCard
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.uploadMessage
import com.monostr.app.ui.common.userMessage
import com.monostr.nostr.Npub
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.Article
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteAddress
import com.monostr.nostr.repo.AddressResult
import com.monostr.nostr.repo.AddressableRepository
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.MentionTags
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.ThreadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One picture attached to the note being written (spec 5.2): uploading, uploaded, or failed. */
data class Attachment(
    val id: Long,
    /** The picked picture as a `content://` URI, or a received one's copy as a `file://` URI; the tile shows it, a retry reads it again. */
    val source: String,
    val progress: Float = 0f,
    val uploaded: UploadedMedia? = null,
    val error: UiText? = null,
    /** Spec 2026-10-27 video §3: the one video of the note. */
    val video: Boolean = false,
    /** The video's encode on the device, 0..1, while it runs; null otherwise. */
    val preparing: Float? = null,
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
    /** Spec 2026-10-16 §8.2: the restored draft's text, once, for the field to take; cleared by [ComposeController.restoreApplied]. */
    val restoredText: String? = null,
    /** Spec 2026-10-29 article reader: the article being commented on, once loaded, with its title for the header. */
    val article: Note? = null,
    val articleTitle: String? = null,
    val articleUnresolved: Boolean = false,
) {
    /** Every picture is on the server: nothing uploading, nothing failed. */
    val attachmentsReady: Boolean get() = attachments.all { it.uploaded != null }
    /** Room for another picture, and the note is not frozen. */
    val canAttach: Boolean get() = attachments.size < NoteMedia.MAX_ATTACH && !sending && !pendingResend
    /** Spec 2026-10-27 video §3: pictures, unless the note has its video. */
    val canAttachPictures: Boolean get() = canAttach && attachments.none { it.video }
    /** Spec 2026-10-27 video §3: one video, on a note without pictures. */
    val canAttachVideo: Boolean get() = attachments.isEmpty() && !sending && !pendingResend
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
    /** A source the note no longer holds: a received picture's copy is deleted (spec 2026-10-16 §3). */
    private val release: (String) -> Unit = {},
    /** Spec 2026-10-16 §8: the drafts of [account]; null in tests without drafts (then leaving discards the pictures as before). */
    private val drafts: DraftStore? = null,
    private val account: String = "",
    /** Outlives the screen: the save on leaving still runs after the screen's scope is cancelled. */
    private val background: CoroutineScope? = null,
    private val draftDelayMs: Long = 1_000,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** pubkey → shown name from the local database only (no network); restored drafts show their mentions with it. */
    private val localNames: suspend (List<String>) -> Map<String, String> = { emptyMap() },
    /** A text the new note starts with (a quote of an article: «\n\nnostr:naddr1…»); it wins over a stored draft's text. */
    val prefill: String? = null,
    /** Spec 2026-10-29 article reader: a comment under this article (kind 1111), optionally answering the comment [commentParentId]. */
    private val articleRef: AddressRef? = null,
    private val commentParentId: String? = null,
    /** Loads the article for [articleRef]; only the user's own relays and the database are asked (the reader just showed it). */
    private val addresses: AddressableRepository? = null,
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
    private val target = DraftTargets.of(replyToId, quoteId, prefill, articleRef?.coordinate, commentParentId)
    /** The field's text as last reported by [onTextChange] (or restored). */
    private var text = ""
    /** False until the stored draft was read: before that nothing is written, so an early close cannot wipe it. */
    private var draftLoaded = false
    private var saveJob: Job? = null
    /** The SHA-256s the stored draft names, as last read or written: a failed leave-save must not delete them. */
    @Volatile private var stored: Set<String> = emptySet()

    fun start() {
        prefill?.takeIf { it.isNotEmpty() }?.let { p ->
            text = p
            _state.update { it.copy(restoredText = p) }
        }
        drafts?.let { store -> scope.launch { restoreDraft(store) } }
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
        if (articleRef != null) scope.launch { loadArticleTarget(articleRef) }
        if (replyToId != null) scope.launch {
            try {
                // spec perf2 C6: the reply target is one note; the thread's root, walk and replies are not needed for it
                threads.note(replyToId)?.let { n -> _state.update { it.copy(replyTo = n) } }
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

    /** The article and, when answering a comment, that comment; a target that does not load blocks the send with the reply-target error. */
    private suspend fun loadArticleTarget(ref: AddressRef) {
        try {
            val found = addresses?.let { (it.cached(ref, false) as? AddressResult.Found) ?: it.get(ref, false) } as? AddressResult.Found
            val article = found?.let { Article.parse(it.event) }
            val parent = commentParentId?.let { threads.note(it) }
            if (article == null || (commentParentId != null && parent == null)) {
                _state.update { it.copy(articleUnresolved = true, error = uiText(R.string.compose_error_reply_target)) }
                return
            }
            _state.update { it.copy(article = article.asNote(), articleTitle = article.title, replyTo = parent) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(articleUnresolved = true, error = uiText(R.string.compose_error_reply_target)) }
        }
    }

    private suspend fun restoreDraft(store: DraftStore) {
        val draft = try {
            store.load(account, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // spec §9: an unreadable store → the composer works as without drafts
        }
        stored = draft?.attachments.orEmpty().mapTo(HashSet()) { it.sha256 }
        if (draft != null) {
            _state.update { s ->
                if (s.attachments.isNotEmpty()) s // the user was faster than the disk: keep what is on screen
                else {
                    // only the server copy is kept: the tile shows the uploaded picture, a local copy is long gone
                    val restored = draft.attachments.map { m -> Attachment(nextAttachmentId++, m.url, progress = 1f, uploaded = m, video = m.isVideo) }
                    s.copy(attachments = restored, sensitive = draft.sensitive && restored.isNotEmpty())
                }
            }
            if (text.isEmpty() && draft.text.isNotEmpty()) {
                text = draft.text
                val names = restoredNames(draft.text)
                _state.update { it.copy(restoredText = draft.text, mentionNames = names + it.mentionNames) }
            }
        }
        draftLoaded = true
    }

    /** Names of the draft's mentions known locally; unknown pubkeys stay raw, a failing lookup restores without names. */
    private suspend fun restoredNames(text: String): Map<String, String> {
        val pubkeys = MentionTags.pubkeys(text)
        if (pubkeys.isEmpty()) return emptyMap()
        return try {
            localNames(pubkeys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** The field took (or ignored) [ComposeUiState.restoredText]. */
    fun restoreApplied() = _state.update { it.copy(restoredText = null) }

    private fun currentDraft() = Draft(text, _state.value.sensitive, _state.value.attachments.mapNotNull { it.uploaded }, now())

    private fun scheduleSave() {
        if (drafts == null || !draftLoaded || consumed) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(draftDelayMs)
            saveDraft(currentDraft())
        }
    }

    /** Spec 2026-10-16 §8.2: right away, e.g. when the screen stops or a picture is removed. */
    fun saveDraftNow() {
        if (drafts == null || !draftLoaded || consumed) return
        saveJob?.cancel()
        val draft = currentDraft()
        (background ?: scope).launch { saveDraft(draft) }
    }

    /** True when written (an empty draft removes the key). */
    private suspend fun saveDraft(draft: Draft): Boolean {
        val store = drafts ?: return false
        return try {
            store.save(account, target, draft)
            stored = draft.attachments.mapTo(HashSet()) { it.sha256 } // an empty draft names nothing
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // spec §9: the composer works as without drafts
        }
    }

    private suspend fun clearDraft() {
        saveJob?.cancel()
        val store = drafts ?: return
        try {
            store.remove(account, target)
            stored = emptySet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // spec §9: best effort
        }
    }

    fun onTextChange(text: String, cursor: Int) {
        if (text != this.text) {
            this.text = text
            scheduleSave()
        }
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
        val up = uploader
        val taken = if (up == null || !_state.value.canAttachPictures) {
            emptyList()
        } else {
            val free = (NoteMedia.MAX_ATTACH - _state.value.attachments.size).coerceAtLeast(0)
            // a picture already on the note, or twice in the pick, is taken once
            val known = _state.value.attachments.mapTo(HashSet()) { it.source }
            sources.filter { known.add(it) }.take(free)
        }
        for (source in taken) {
            val id = nextAttachmentId++
            _state.update { it.copy(attachments = it.attachments + Attachment(id, source), error = null) }
            startUpload(up!!, id, source)
        }
        sources.filterNot { it in taken || _state.value.attachments.any { a -> a.source == it } }.forEach(release)
    }

    /**
     * Spec 2026-10-27 video §3: the picked video, when the note has room for it and it lasts at most two minutes (else
     * the message, and no tile); it is encoded and uploaded at once.
     */
    fun attachVideo(source: String) {
        val up = uploader
        if (up == null || !_state.value.canAttachVideo) {
            release(source)
            return
        }
        scope.launch {
            val duration = try {
                up.videoDurationMs(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.uploadMessage()) }
                release(source)
                return@launch
            }
            if (VideoRules.tooLong(duration)) {
                _state.update { it.copy(error = uiText(R.string.compose_video_too_long)) }
                release(source)
                return@launch
            }
            if (!_state.value.canAttachVideo) return@launch release(source) // something got on the note meanwhile
            val id = nextAttachmentId++
            _state.update { it.copy(attachments = it.attachments + Attachment(id, source, video = true, preparing = 0f), error = null) }
            startVideoUpload(up, id, source)
        }
    }

    /** The tile's progress is half encode, half upload. */
    private fun startVideoUpload(up: MediaUploader, id: Long, source: String) {
        uploads[id] = scope.launch {
            try {
                val media = up.uploadVideo(
                    source,
                    onPreparing = { p -> patch(id) { it.copy(preparing = p, progress = p / 2) } },
                    onProgress = { p -> patch(id) { it.copy(preparing = null, progress = 0.5f + p / 2) } },
                )
                patch(id) { it.copy(uploaded = media, preparing = null, progress = 1f) }
                scheduleSave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is UploadException.TooLarge) uiText(R.string.upload_video_too_large) else e.uploadMessage()
                patch(id) { it.copy(error = message, preparing = null) }
            }
        }
    }

    private fun startUpload(up: MediaUploader, id: Long, source: String) {
        uploads[id] = scope.launch {
            try {
                val media = up.upload(source, ImageTarget.NOTE) { p -> patch(id) { it.copy(progress = p) } }
                patch(id) { it.copy(uploaded = media, progress = 1f) }
                scheduleSave()
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
        if (a.video) startVideoUpload(up, id, a.source) else startUpload(up, id, a.source)
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
        release(a.source)
        // the removed picture is gone from the server: the stored draft must not name it any more
        saveDraftNow()
    }

    /** The composer is gone: the local copies of received pictures go too, sent or not. */
    fun releaseSources() = _state.value.attachments.forEach { release(it.source) }

    fun setSensitive(on: Boolean) {
        _state.update { if (it.sending) it else it.copy(sensitive = on && it.attachments.isNotEmpty()) }
        scheduleSave()
    }

    /**
     * The composer is being left. Spec 2026-10-16 §8.2: the unsent note stays as the draft of its target,
     * pictures included, so nothing is deleted at the server. Without a draft store (or when it cannot be
     * written, or the stored draft was not read yet) the pictures of a note that was never sent are deleted
     * as before (spec 5.2), except those the stored draft still names. Pictures of a note that went out, or waits for a resend, stay. Starts nothing on
     * [scope]: it is called from `onCleared`, where that scope is already cancelled.
     */
    fun discardUnsent() {
        if (consumed) return
        uploads.values.forEach { it.cancel() }
        uploads.clear()
        saveJob?.cancel()
        val uploaded = _state.value.attachments.mapNotNull { it.uploaded }.distinctBy { it.sha256 }
        val bg = background
        if (drafts == null || bg == null || !draftLoaded) {
            uploaded.forEach { uploader?.discard(it) }
            return
        }
        val draft = currentDraft()
        bg.launch {
            // the draft stored before still names its pictures: only the others go
            if (!saveDraft(draft)) uploaded.filter { it.sha256 !in stored }.forEach { uploader?.discard(it) }
        }
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
        if (articleRef != null && _state.value.article == null) {
            _state.update { it.copy(error = uiText(R.string.compose_error_reply_target)) }
            return
        }
        // synchronously, before the launch: the pictures are frozen and protected from this tap on
        val firstSend = pendingEventId == null
        consumed = true
        saveJob?.cancel() // a pending save must not bring back the draft this send takes along
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
                    articleRef != null -> publish.comment(text, checkNotNull(_state.value.article), parent, media, sensitive)
                    parent != null -> publish.reply(text, parent, media, sensitive)
                    quoted != null -> publish.quote(text, quoted, media, sensitive)
                    else -> publish.post(text, media, sensitive)
                }
                pendingEventId = if (result.sentToAny) null else result.eventId
                // spec 2026-10-16 §8.2: sent, or stored for a resend that names the pictures: the draft is done
                clearDraft()
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
                scheduleSave() // the save cancelled by the tap on Send
            }
        }
    }
}

@HiltViewModel
class ComposeViewModel @Inject constructor(
    session: NostrSession,
    handle: SavedStateHandle,
    @ApplicationContext context: Context,
    drafts: DraftStore,
) : ViewModel() {
    private val ready = session.requireReady()
    val quoteId: String? = handle["quote"]
    private val received = ReceivedImages.dir(context)
    val controller = ComposeController(
        handle["replyTo"], ready.threads, ready.publish, viewModelScope, quoteId = quoteId,
        suggester = MentionSuggester(ready.feed, ready.profiles, ready.search, ready.pubkey),
        uploader = ready.media,
        release = { ReceivedImages.release(it, received) },
        drafts = drafts, account = ready.pubkey, background = ready.engine.backgroundScope,
        localNames = { keys -> ready.profiles.local(keys).associate { it.pubkey to it.shownName } },
        prefill = handle["text"],
        articleRef = handle.get<String>("article")?.let { NoteAddress.decode("nostr:$it") },
        commentParentId = handle["parent"],
        addresses = ready.addresses,
    )
    val prefill: String? = handle["text"]
    init {
        ReceivedImages.sweep(received, System.currentTimeMillis())
        controller.start()
    }

    // lifecycle 2.11 cancels viewModelScope before this runs; discardUnsent starts nothing on it
    override fun onCleared() {
        controller.discardUnsent()
        controller.releaseSources()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComposeScreen(onDone: () -> Unit, vm: ComposeViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    // spec 2026-10-16 §3: the state-based field (it can receive pasted pictures); saved across rotation and process death
    val field = rememberTextFieldState()
    val mentionOutput = remember(state.mentionNames) { MentionOutput(state.mentionNames) }
    val mentionInput = remember(state.mentionNames) { MentionInput(state.mentionNames) }
    val context = LocalContext.current
    // spec 2026-10-16 §3: pictures pasted (long press → Paste, clipboard suggestions) or inserted by the keyboard join the note;
    // the URIs are taken here: pictures attach as copies (the grant is temporary), pictures without room are left so the
    // keyboard learns they were not taken, other URIs are dropped; text stays for the field, which never inserts a URI
    val receiver = remember(vm, context) {
        val dir = ReceivedImages.dir(context)
        ReceiveContentListener { content ->
            val now = vm.controller.state.value
            var room = if (now.canAttachPictures) NoteMedia.MAX_ATTACH - now.attachments.size else 0
            val picked = ArrayList<String>()
            val rest = content.consume { item ->
                val uri = item.uri ?: return@consume false
                val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                val image = ReceivedImages.accept(uri.scheme, uri.authority, type, context.packageName)
                val attached = image && room > 0
                if (attached) {
                    room--
                    picked += ReceivedImages.keep(context.contentResolver, uri, type!!, dir)
                }
                ReceivedImages.taken(image, attached, hasText = item.text != null)
            }
            if (picked.isNotEmpty()) vm.controller.attach(picked)
            rest
        }
    }
    // every change of text or cursor — typing, paste, keyboard, a picked mention — reaches the controller
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() to field.selection.end }.collect { (text, cursor) -> vm.controller.onTextChange(text, cursor) }
    }
    // spec 2026-10-16 §8.2: the draft's text with the cursor at the end; a text the field already has (rotation, process death) wins
    LaunchedEffect(state.restoredText) {
        val restored = state.restoredText ?: return@LaunchedEffect
        // a prefill (the quoted article's link) leaves the cursor in front, where the user's own words go
        if (field.text.isEmpty()) {
            if (restored == vm.prefill) field.edit { replace(0, length, restored); selection = TextRange(0) } else field.setTextAndPlaceCursorAtEnd(restored)
        }
        vm.controller.restoreApplied()
    }
    // leaving the app with the composer open saves at once, not a second later
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.controller.saveDraftNow() }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    // the system photo picker needs no permission (spec 5.2); it hands back content:// URIs
    val onPicked: (List<Uri>) -> Unit = { uris -> if (uris.isNotEmpty()) vm.controller.attach(uris.map { it.toString() }) }
    // spec 5.2: the picker offers exactly the free places, so one launcher per possible count, all registered
    // at every composition (key: each its own saved registry key); the multi picker cannot be asked for one
    val pickMany = (2..NoteMedia.MAX_ATTACH).associateWith { n ->
        key(n) { rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(n), onPicked) }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onPicked(listOfNotNull(uri)) }
    val imagesOnly = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }
    // spec 2026-10-27 video §3: exactly one video
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.controller.attachVideo(it.toString()) } }
    val videoOnly = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (state.replyTo != null || state.article != null) R.string.compose_title_reply else R.string.compose_title_new)) },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).imePadding().padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            // the comment answered, else the article commented on
            (state.replyTo?.displayContent ?: state.articleTitle)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, modifier = Modifier.testTag("compose-article-target"))
                Spacer(Modifier.height(12.dp))
            }
            // after an offline send the stored event is re-sent as is, so the text is frozen
            OutlinedTextField(
                state = field,
                readOnly = state.pendingResend,
                inputTransformation = mentionInput,
                outputTransformation = mentionOutput,
                modifier = Modifier.fillMaxWidth().height(200.dp).contentReceiver(receiver).testTag("compose-text"),
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
                                vm.controller.pick(field.text.toString(), field.selection.end, p)?.let { e ->
                                    field.edit { replace(0, length, e.text); selection = TextRange(e.cursor) }
                                }
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
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = state.sensitive, onCheckedChange = vm.controller::setSensitive,
                        enabled = !state.sending && !state.pendingResend, modifier = Modifier.testTag("compose-sensitive"),
                    )
                }
            }
            Row {
                IconButton(
                    onClick = {
                        val free = NoteMedia.MAX_ATTACH - state.attachments.size
                        val many = pickMany[free]
                        if (many != null) many.launch(imagesOnly) else pickOne.launch(imagesOnly)
                    },
                    enabled = state.canAttachPictures,
                    modifier = Modifier.testTag("compose-attach"),
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.compose_attach))
                }
                IconButton(
                    onClick = { pickVideo.launch(videoOnly) },
                    enabled = state.canAttachVideo,
                    modifier = Modifier.testTag("compose-attach-video"),
                ) {
                    Icon(Icons.Outlined.VideoLibrary, contentDescription = stringResource(R.string.compose_add_video))
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.controller.send(field.text.toString()) },
                enabled = !state.sending && (field.text.isNotBlank() || vm.quoteId != null || state.attachments.isNotEmpty()) &&
                    state.attachmentsReady && !state.replyUnresolved && !state.quoteUnresolved && !state.articleUnresolved,
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
