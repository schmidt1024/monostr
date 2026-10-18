package com.monostr.app.ui.lists

import com.monostr.app.R
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.attempt
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import com.monostr.nostr.repo.FeedTabsRepository
import com.monostr.nostr.repo.ListsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListEditState(
    val title: String = "",
    val description: String = "",
    val private: Boolean = true,
    val isNew: Boolean = true,
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** "Everyone will see who is on this list." is open (spec §2.4). */
    val confirmPublic: Boolean = false,
    val confirmDelete: Boolean = false,
    /** Saved or deleted: the screen closes; [deleted] closes the list screen too. */
    val done: Boolean = false,
    val deleted: Boolean = false,
    val message: Message? = null,
) {
    val canSave: Boolean get() = title.isNotBlank() && !saving && !loading
}

/** Spec 2026-10-18 §2.4: a new list ([address] null) or an own existing one. */
class ListEditController(
    private val address: ListAddress?,
    private val self: String,
    /** "New list…" in the add-to-list sheet: this account is the new list's first member (spec §2.5). */
    private val firstMember: String?,
    private val lists: ListsRepository,
    private val pins: FeedTabsRepository,
    private val scope: CoroutineScope,
    private val writeScope: CoroutineScope = scope,
) {
    private val _state = MutableStateFlow(ListEditState(isNew = address == null, loading = address != null))
    val state: StateFlow<ListEditState> = _state.asStateFlow()
    private var original: FollowSet? = null

    fun start() {
        val address = address ?: return
        scope.launch {
            val set = attempt { lists.byAddress(address) }
            original = set
            if (set == null) {
                _state.update { it.copy(loading = false, done = true) } // gone meanwhile: nothing to edit
            } else {
                _state.update { it.copy(title = set.title.take(MAX_TITLE), description = set.description.orEmpty().take(MAX_DESCRIPTION), private = set.private, loading = false) }
            }
        }
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value.take(MAX_TITLE)) }
    fun setDescription(value: String) = _state.update { it.copy(description = value.take(MAX_DESCRIPTION)) }

    /** Private → public on a list that exists asks first (spec §2.4); a new list switches at once. */
    fun setPrivate(on: Boolean) {
        if (!on && _state.value.private && original?.private == true) _state.update { it.copy(confirmPublic = true) }
        else _state.update { it.copy(private = on) }
    }

    fun confirmPublic() = _state.update { it.copy(private = false, confirmPublic = false) }
    fun dismissPublic() = _state.update { it.copy(confirmPublic = false) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val title = s.title.trim()
        val description = s.description.trim().ifBlank { null }
        val existing = original?.address
        // an existing list keeps its members as they are at save time (ticks in the add-to-list sheet meanwhile)
        val write: suspend () -> SaveResult = if (existing != null) {
            { lists.edit(existing, title, description, s.private) }
        } else {
            val set = FollowSet(ListAddress(ListAddress.FOLLOW_SET, self, lists.newId()), title, description, listOfNotNull(firstMember), s.private, self, 0, false)
            ({ lists.save(set) })
        }
        _state.update { it.copy(saving = true) }
        writeScope.launch {
            val r = attempt { write() } ?: SaveResult.Failed
            if (r == SaveResult.Ok) _state.update { it.copy(saving = false, done = true) }
            else _state.update { it.copy(saving = false, message = Message(uiText(R.string.list_save_failed), retry = ::save)) }
        }
    }

    fun askDelete() = _state.update { it.copy(confirmDelete = true) }
    fun dismissDelete() = _state.update { it.copy(confirmDelete = false) }

    fun delete() {
        val set = original ?: return
        _state.update { it.copy(confirmDelete = false, saving = true) }
        writeScope.launch {
            val r = attempt { lists.delete(set) } ?: SaveResult.Failed
            if (r == SaveResult.Ok) {
                // spec §3.2.3: a deleted list leaves the tabs; best effort, an unavailable tab can still be unpinned by hand
                attempt { pins.unpin(set.address) }
                _state.update { it.copy(saving = false, done = true, deleted = true) }
            } else {
                _state.update { it.copy(saving = false, message = Message(uiText(R.string.list_save_failed), retry = ::delete)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        const val MAX_TITLE = 50
        const val MAX_DESCRIPTION = 200
    }
}
