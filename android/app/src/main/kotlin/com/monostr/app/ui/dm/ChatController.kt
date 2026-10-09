package com.monostr.app.ui.dm

import kotlinx.coroutines.flow.map
import com.monostr.app.ui.common.MentionResolver
import com.monostr.app.R
import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.DmStore
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One bubble: the stored message and a display-only [Note] carrying its text without media URLs and the parsed media. */
data class ChatMessageUi(val message: DmMessage, val note: Note)

data class ChatUiState(
    val peer: Profile,
    val messages: List<ChatMessageUi> = emptyList(),
    val loading: Boolean = true,
    val sending: Boolean = false,
    val message: UiText? = null,
)

/**
 * One NIP-17 conversation with [peer]. Rows come from the local store only: [send] and [retry]
 * write SENDING/SENT/FAILED there themselves, so the list follows without extra bookkeeping.
 * While the chat is visible, every emission moves the read marker to [now] (or the newest message, if later, capped at a day ahead).
 */
class ChatController(
    private val peer: String,
    private val me: String,
    private val store: DmStore,
    private val profiles: ProfileRepository,
    send: suspend (String, String) -> DmStatus,
    retry: suspend (String) -> DmStatus,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val mentions: MentionResolver = MentionResolver(profiles, scope),
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val sendFn = send
    private val retryFn = retry
    private val _state = MutableStateFlow(ChatUiState(Profile.empty(peer)))
    @Volatile private var visible = false
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    fun start() {
        mentions.follow(_state.map { s -> s.messages.map { it.note } })
        scope.launch {
            val profile = guarded(null) { profiles.get(peer) }
            if (profile != null) _state.update { it.copy(peer = profile) }
        }
        scope.launch {
            try {
                // distinct: moving the read marker makes the store emit the same rows again
                store.messages(peer).distinctUntilChanged().collect { list ->
                    _state.update { s -> s.copy(messages = list.map { ChatMessageUi(it, it.asNote()) }, loading = false) }
                    if (visible) markReadNow()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = uiText(R.string.messages_error_load)) }
            }
        }
    }

    /** The screen is started (in the foreground): only then do shown messages count as read. */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (visible && !_state.value.loading) markRead()
    }

    fun send(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(sending = true) }
        scope.launch {
            try {
                report(guarded(DmStatus.FAILED) { sendFn(peer, text) })
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun retry(rumorId: String) {
        scope.launch { report(guarded(DmStatus.FAILED) { retryFn(rumorId) }) }
    }

    fun markRead() {
        if (visible) scope.launch { markReadNow() }
    }

    /**
     * Up to now, or to the newest message when a peer's clock runs ahead so that one does not stay
     * unread; a timestamp further ahead moves the marker [MAX_SKEW] at most.
     */
    private suspend fun markReadNow() = guarded(Unit) {
        val at = now()
        val newest = _state.value.messages.maxOfOrNull { it.message.createdAt } ?: 0L
        store.markRead(peer, maxOf(at, minOf(newest, at + MAX_SKEW)))
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun report(status: DmStatus) {
        if (status == DmStatus.FAILED) _state.update { it.copy(message = uiText(R.string.chat_error_send)) }
    }

    /** The author is for display only: own bubbles carry [me], the others [peer]. */
    private fun DmMessage.asNote(): Note {
        val parsed = NoteMedia.parse(content, emptyList())
        return Note(
            rumorId, if (outgoing) me else peer, content, createdAt, 1, null, null, null, emptyList(),
            media = parsed.media, displayContent = parsed.displayContent,
            quotedId = parsed.quote?.id, quoteRelays = parsed.quote?.relays.orEmpty(),
        )
    }

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    private companion object {
        const val MAX_SKEW = 86_400L
    }
}
