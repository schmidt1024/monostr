package com.monostr.app.ui.lists

import com.monostr.app.R
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.attempt
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import com.monostr.nostr.repo.ListsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddToListRow(val set: FollowSet, val checked: Boolean)

data class AddToListState(
    /** Null until the own lists are read. */
    val rows: List<AddToListRow>? = null,
    val message: Message? = null,
)

/**
 * Spec 2026-10-18 §2.5: [pubkey] in or out of each own list. A tick shows at once; the repository runs the
 * writes per list one after another. Writes go to [writeScope] (the engine's background scope), so leaving
 * the sheet right after a tick neither cancels nor rolls it back.
 */
class AddToListController(
    private val pubkey: String,
    private val lists: ListsRepository,
    private val scope: CoroutineScope,
    private val writeScope: CoroutineScope = scope,
) {
    private val _state = MutableStateFlow(AddToListState())
    val state: StateFlow<AddToListState> = _state.asStateFlow()

    /** Ticks still on their way, per list: the wanted state and the tick's number (only the latest one clears it). */
    private val pending = MutableStateFlow<Map<ListAddress, Pair<Boolean, Int>>>(emptyMap())
    private var seq = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(lists.own(), pending) { own, p ->
                own.map { s -> AddToListRow(s, p[s.address]?.first ?: (pubkey in s.members)) }
            }.collect { rows -> _state.update { it.copy(rows = rows) } }
        }
    }

    fun toggle(row: AddToListRow) {
        if (row.set.locked) return
        write(row.set.address, !row.checked)
    }

    private fun write(address: ListAddress, want: Boolean) {
        val n = ++seq
        pending.update { it + (address to (want to n)) }
        writeScope.launch {
            val r = attempt { lists.setMember(address, pubkey, want) } ?: SaveResult.Failed
            pending.update { m -> if (m[address]?.second == n) m - address else m }
            if (r != SaveResult.Ok) _state.update { it.copy(message = Message(uiText(R.string.list_save_failed), retry = { write(address, want) })) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
