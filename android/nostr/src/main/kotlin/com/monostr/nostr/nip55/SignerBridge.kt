package com.monostr.nostr.nip55

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Hands NIP-55 intent requests to whatever Activity is in the foreground and
 * suspends until its result arrives. One request at a time.
 */
class SignerBridge {
    data class Pending(val id: String, val request: Nip55.Request)

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<Pending?>(null)
    private var waiting: CompletableDeferred<Pair<Boolean, Nip55.Response?>>? = null

    /** The Activity collects this and launches the intent for each non-null value. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    suspend fun request(request: Nip55.Request): Pair<Boolean, Nip55.Response?> = mutex.withLock {
        val deferred = CompletableDeferred<Pair<Boolean, Nip55.Response?>>()
        waiting = deferred
        _pending.value = Pending(UUID.randomUUID().toString(), request)
        try {
            deferred.await()
        } finally {
            _pending.value = null
            waiting = null
        }
    }

    /**
     * Called by the Activity with the activity result. [id] must match the currently
     * pending request's id; a stale or unrelated completion (e.g. a delayed callback
     * after a new request already started, or a repeat call) is ignored.
     */
    fun complete(id: String, resultOk: Boolean, response: Nip55.Response?) {
        if (id != _pending.value?.id) return
        waiting?.complete(resultOk to response)
    }
}
