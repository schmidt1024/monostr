package com.monostr.app.ui.dm

import com.monostr.app.R
import com.monostr.app.data.dm.Conversation
import com.monostr.app.data.dm.DmStore
import com.monostr.app.dm.DmSyncState
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.FeedRepository
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUi(val conversation: Conversation, val profile: Profile, val request: Boolean)

data class ConversationsUiState(
    val items: List<ConversationUi> = emptyList(),
    val requests: List<ConversationUi> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val authFailed: Set<String> = emptySet(),
    /** Own inbox relays that refuse our sign-in itself, with the relay's reason. */
    val authRejected: Map<String, String> = emptyMap(),
    val pending: Int = 0,
    val message: UiText? = null,
)

/**
 * The messages tab: conversations from the local store, split into followed/answered chats and
 * requests (a stranger we never replied to). Amber is only asked on [unlockPending], a user tap.
 */
class ConversationsController(
    private val store: DmStore,
    private val profiles: ProfileRepository,
    private val feed: FeedRepository,
    private val syncState: StateFlow<DmSyncState>,
    private val pendingCount: Flow<Int>,
    private val onRefresh: suspend () -> Unit,
    private val onUnlock: suspend () -> Int,
    private val scope: CoroutineScope,
    /** Peers whose conversations are hidden (spec 9.3); the messages stay in the store. */
    private val muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
) {
    private val _state = MutableStateFlow(ConversationsUiState())
    val state: StateFlow<ConversationsUiState> = _state.asStateFlow()

    fun start() {
        scope.launch { syncState.collect { s -> _state.update { it.copy(authFailed = s.authFailed, authRejected = s.authRejected) } } }
        scope.launch { pendingCount.collect { n -> _state.update { it.copy(pending = n) } } }
        scope.launch {
            val follows = try {
                feed.follows().toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
            try {
                store.conversations().combine(muted) { conversations, m -> conversations.filter { it.peer !in m } }.collect { conversations ->
                    val decorated = decorate(conversations, follows)
                    _state.update { s ->
                        s.copy(items = decorated.filterNot { it.request }, requests = decorated.filter { it.request }, loading = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = uiText(R.string.messages_error_load)) }
            }
        }
    }

    private suspend fun decorate(conversations: List<Conversation>, follows: Set<String>): List<ConversationUi> {
        val peers = conversations.map { it.peer }
        guarded(Unit) { profiles.prefetch(peers) }
        return conversations.map { c ->
            val profile = guarded(Profile.empty(c.peer)) { profiles.get(c.peer) }
            ConversationUi(c, profile, request = c.peer !in follows && !c.hasOutgoing)
        }
    }

    /** A failing profile lookup or sync never breaks the list: [fallback] stands in (a peer then shows with the default profile). */
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        scope.launch {
            try {
                guarded(Unit) { onRefresh() }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun unlockPending() {
        scope.launch { guarded(0) { onUnlock() } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
