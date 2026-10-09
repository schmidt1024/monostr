package com.monostr.app.ui.common

import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Spec 2, one per screen: resolves the mention names of what the screen shows. Each pubkey is
 * looked up once per resolver; names arrive as a state update, so the note text re-renders
 * without a second lookup. Pubkeys without a real name stay out of [names] (short form in the UI).
 * [scanDispatcher]: where [follow] scans note texts; the ViewModels pass `Dispatchers.Default`.
 */
class MentionResolver(
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope,
    private val scanDispatcher: CoroutineContext = EmptyCoroutineContext,
) {
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()
    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Note ids already scanned by [follow], oldest first; guarded by itself. */
    private val seen = LinkedHashSet<String>()

    fun track(pubkeys: Collection<String>) {
        if (pubkeys.isEmpty()) return
        scope.launch { resolve(pubkeys) }
    }

    /** Looks up the pubkeys not requested before; false when the lookup failed (they may be requested again). */
    private suspend fun resolve(pubkeys: Collection<String>): Boolean {
        val fresh = pubkeys.filter { requested.add(it) }
        if (fresh.isEmpty()) return true
        val resolved = try {
            MentionNames.resolve(profiles, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            requested.removeAll(fresh.toSet()) // a later batch may try again
            return false
        }
        if (resolved.isNotEmpty()) _names.update { it + resolved }
        return true
    }

    /**
     * Spec 8 (10c backlog): the one collector every list uses. Only notes whose id was not seen
     * before are scanned, on [scanDispatcher]; at most [SEEN_CAP] ids are remembered (oldest out),
     * so a note that comes back after that is scanned once more. When the lookup fails, the notes
     * are forgotten again, so the next emission scans them once more.
     */
    fun follow(notes: Flow<List<Note>>) {
        scope.launch {
            notes.collect { list ->
                val fresh = synchronized(seen) {
                    list.filter { seen.add(it.id) }.also { while (seen.size > SEEN_CAP) seen.remove(seen.first()) }
                }
                if (fresh.isEmpty()) return@collect
                val ok = resolve(withContext(scanDispatcher) { MentionNames.collect(fresh) })
                if (!ok) synchronized(seen) { fresh.forEach { seen.remove(it.id) } }
            }
        }
    }

    internal fun seenCount(): Int = synchronized(seen) { seen.size }

    companion object {
        const val SEEN_CAP = 2_000
    }
}
