package com.monostr.app.ui.dm

import kotlinx.coroutines.flow.map
import com.monostr.app.ui.common.MentionResolver
import com.monostr.app.R
import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.DmStore
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
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
 * While the chat is visible, every emission moves the read marker to [now] (or the newest message, if later, capped at a day ahead);
 * only the newest [PAGE] messages are loaded until [loadOlder].
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
    /** Where rows become bubbles (NoteMedia.parse per message): off the main thread (spec perf2 D5). Tests pass theirs. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val sendFn = send
    private val retryFn = retry
    private val _state = MutableStateFlow(ChatUiState(Profile.empty(peer)))
    @Volatile private var visible = false
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** How many of the newest messages are loaded; grows by [PAGE] in [loadOlder]. */
    private val limit = MutableStateFlow(PAGE)
    /**
     * The last bubbles by rumor id: an unchanged message keeps its bubble (no second parse). Only the mapping step touches it.
     * Holds decrypted text in memory for the open chat only; dropped when the collection ends, never logged.
     */
    private var bubbles: Map<String, ChatMessageUi> = emptyMap()

    /** Spec perf2 §7.1: the chat's first loaded state is marked once. */
    private var shownMarked = false
    private fun markShown() { if (!shownMarked) { shownMarked = true; PerfTrace.mark("chat first messages") } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        mentions.follow(_state.map { s -> s.messages.map { it.note } })
        // subscribed first: the peer's profile can land while the stored one is read (spec perf 3.2)
        scope.launch { profiles.updates.collect { byKey -> byKey[peer]?.let { p -> _state.update { it.copy(peer = p) } } } }
        scope.launch {
            val profile = guarded(null) { profiles.get(peer) }
            if (profile != null && profile != Profile.empty(peer)) _state.update { it.copy(peer = profile) }
        }
        scope.launch {
            try {
                // distinct: moving the read marker makes the store emit the same rows again
                limit.flatMapLatest { n -> store.messages(peer, n) }
                    .distinctUntilChanged()
                    .map { list -> bubblesFor(list) }
                    .flowOn(work)
                    .collect { rows ->
                        _state.update { s -> s.copy(messages = rows, loading = false) }
                        markShown()
                        if (visible) markReadNow()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = uiText(R.string.messages_error_load)) }
                markShown()
            } finally {
                bubbles = emptyMap()
            }
        }
    }

    /** Spec perf2 D5: [PAGE] more older messages, only when the newest page came back full (else everything is loaded). */
    fun loadOlder() {
        val shown = _state.value.messages.size
        limit.update { n -> if (shown >= n) n + PAGE else n }
    }

    private fun bubblesFor(list: List<DmMessage>): List<ChatMessageUi> {
        val previous = bubbles
        val next = HashMap<String, ChatMessageUi>(list.size)
        val rows = list.map { m -> (previous[m.rumorId]?.takeIf { it.message == m } ?: ChatMessageUi(m, m.asNote())).also { next[m.rumorId] = it } }
        bubbles = next
        return rows
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
            quotedId = parsed.quote?.id, quoteRelays = parsed.quote?.relays.orEmpty(), quoteAuthor = parsed.quote?.author,
            addressRef = parsed.address,
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
        const val PAGE = 100
    }
}
