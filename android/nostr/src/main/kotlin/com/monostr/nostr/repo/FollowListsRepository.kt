package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import java.time.Duration

/** One page of followers and where the next one starts; [nextUntil] null = no further page. */
data class FollowersPage(val pubkeys: List<String>, val nextUntil: Long?)

/** The accounts behind the profile's two numbers (spec 2026-10-30 follow lists). */
interface FollowListsRepository {
    /** Whom [pubkey] follows, newest follow first; empty when no contact list is known. */
    suspend fun following(pubkey: String): List<String>
    /**
     * One page of accounts whose contact list names [pubkey], newest list first, from [until] (inclusive) down;
     * accounts in [seen] are left out. Never complete: only what the relays asked hold.
     */
    suspend fun followers(pubkey: String, until: Long?, seen: Set<String>): FollowersPage
}

/**
 * Following: the same newest kind 3 the count reads (fetched from the normal and index relays when missing or older
 * than a day). Followers: kind 3 with `#p` from the count relays and the user's relays, [pageSize] at a time. The
 * client stores every fetched event, and contact lists are large (up to ~100 KB): the ones fetched only for this list
 * are deleted again; lists of [kept] accounts (the user and whom they follow) and lists stored before stay.
 */
class NostrFollowListsRepository(
    private val engine: NostrEngine,
    private val kept: suspend () -> Set<String>,
    private val countRelays: List<String> = NostrFollowCountsRepository.COUNT_RELAYS,
    private val indexRelays: List<String> = NostrProfileRepository.INDEX_RELAYS,
    private val timeout: Duration = Duration.ofSeconds(8),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val pageSize: Int = PAGE_SIZE,
) : FollowListsRepository {

    override suspend fun following(pubkey: String): List<String> {
        val filter = Filter().kind(Kind(3u)).author(PublicKey.parse(pubkey)).limit(1u)
        var list = engine.query(filter).firstOrNull()
        if (list == null || now() - list.createdAt().asSecs().toLong() > NostrFollowCountsRepository.LIST_MAX_AGE_SECONDS) {
            try {
                fetchWide(engine, filter, timeout) { indexRelays }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            list = engine.query(filter).firstOrNull() ?: list
        }
        return list?.let { newestFirst(NoteMapper.decoded(it).tags) }.orEmpty()
    }

    override suspend fun followers(pubkey: String, until: Long?, seen: Set<String>): FollowersPage {
        val target = PublicKey.parse(pubkey)
        val named = Filter().kind(Kind(3u)).pubkey(target)
        // which of these lists the database held before: those stay
        val before = engine.query(named).mapTo(HashSet()) { it.id().toHex() }
        var filter = named.limit(pageSize.toULong())
        if (until != null) filter = filter.until(Timestamp.fromSecs(until.toULong()))
        val relays = (countRelays + engine.relayUrls()).map { it.trim().trimEnd('/') }.distinct()
        val events = try {
            // a page is there once [pageSize] lists arrived (spec perf: < 3 s); waiting for the slowest relay took 9 s on the Pixel
            engine.fetchFrom(relays, filter, timeout, exit = Exit.AtLeast(pageSize))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val keep = kept()
        val passing = events.filter { it.id().toHex() !in before && it.author().toHex() !in keep }.map(Event::author)
        if (passing.isNotEmpty()) engine.forget(Filter().kind(Kind(3u)).authors(passing))
        return page(events.map { it.author().toHex() to it.createdAt().asSecs().toLong() }, seen)
    }

    companion object {
        const val PAGE_SIZE = 30
        private val HEX64 = Regex("[0-9a-f]{64}")

        /** The followed accounts of a contact list's tags: a follow is appended, so the end comes first; each account once. */
        fun newestFirst(tags: List<List<String>>): List<String> =
            tags.filter { it.size >= 2 && it[0] == "p" && HEX64.matches(it[1]) }.map { it[1] }.asReversed().distinct()

        /**
         * The page from (author, created_at) pairs: newest first, without [seen] and duplicates. The next page starts at
         * the oldest time here (inclusive, so lists of the same second are not skipped); a page without anything new ends the list.
         */
        fun page(found: List<Pair<String, Long>>, seen: Set<String>): FollowersPage {
            val fresh = found.sortedByDescending { it.second }.distinctBy { it.first }.filter { it.first !in seen }
            if (fresh.isEmpty()) return FollowersPage(emptyList(), null)
            return FollowersPage(fresh.map { it.first }, found.minOf { it.second })
        }
    }
}
