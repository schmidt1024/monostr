package com.monostr.app.ui.monero

import com.monostr.nostr.repo.OwnMonero
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Owner 2026-11-27 (backlog 121): after a login the app goes to the feed. The Monero intro follows only once the
 * account's relays said for sure that nothing is set up; an account that already receives Monero never sees it, and
 * an unclear answer (relays not reached, Tor still starting) shows nothing now and is asked again at the next start.
 * An account created here has nothing anywhere: its intro comes at once.
 */
object MoneroIntroGate {
    /**
     * An unclear answer is asked again this often: [FIRST_PAUSE_MS] after the first one (right after a login the relays
     * are still connecting), then [PAUSE_MS] apart, so a slow Tor start falls inside.
     */
    const val TRIES = 5
    const val FIRST_PAUSE_MS = 3_000L
    const val PAUSE_MS = 10_000L

    /**
     * Device tests switch this off ([com.monostr.app.MonostrTestRunner]): their login helpers expect the intro at once
     * or not at all, never seconds later over the screen they are working on.
     */
    @Volatile var askFirst = true

    /** True when the intro is due. [markSeen] runs when the account turned out to be set up. */
    suspend fun due(
        created: Boolean,
        lookup: suspend () -> OwnMonero,
        markSeen: suspend () -> Unit,
        tries: Int = TRIES,
        firstPauseMs: Long = FIRST_PAUSE_MS,
        pauseMs: Long = PAUSE_MS,
    ): Boolean {
        if (created || !askFirst) return true
        repeat(tries) { round ->
            val answer = try {
                lookup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OwnMonero.UNKNOWN
            }
            when (answer) {
                OwnMonero.FOUND -> {
                    markSeen()
                    return false
                }
                OwnMonero.NONE -> return true
                OwnMonero.UNKNOWN -> if (round < tries - 1) delay(if (round == 0) firstPauseMs else pauseMs)
            }
        }
        return false
    }
}
