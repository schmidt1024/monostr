package com.monostr.nostr.repo

import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.untilFirst
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Spec 2026-10-29 article reader: the replies list's own `#A` / `#a` fetch. */
class ArticleRepliesRepositoryTest {
    @TempDir lateinit var dir: Path
    private val author = Keys.generate()
    private val bob = Keys.generate()
    private var clock = 1000L
    private val ref = AddressRef(30023, author.publicKey().toHex(), "post")
    private val articleId = "d".repeat(64)

    private fun at() = Timestamp.fromSecs((clock++).toULong())

    private fun comment(text: String, keys: Keys = bob, parent: Event? = null): Event {
        val root = listOf(listOf("A", ref.coordinate), listOf("E", articleId), listOf("K", "30023"), listOf("P", ref.pubkey))
        val scope = if (parent == null) listOf(listOf("a", ref.coordinate), listOf("e", articleId), listOf("k", "30023"))
        else listOf(listOf("e", parent.id().toHex()), listOf("k", "1111"))
        return EventBuilder(Kind(1111u), text).tags((root + scope).map { Tag.parse(it) }).customCreatedAt(at()).signWithKeys(keys)
    }

    private fun oldReply(text: String) =
        EventBuilder.textNote(text).tags(listOf(Tag.parse(listOf("a", ref.coordinate)))).customCreatedAt(at()).signWithKeys(bob)

    private fun unrelated(text: String) =
        EventBuilder.textNote(text).tags(listOf(Tag.parse(listOf("a", "30023:${ref.pubkey}:other")))).customCreatedAt(at()).signWithKeys(bob)

    @Test
    fun `the filters ask kind 1111 by A and kind 1 by a`() {
        val c = NostrArticleRepliesRepository.commentsFilter(ref.coordinate).asJson()
        val n = NostrArticleRepliesRepository.notesFilter(ref.coordinate).asJson()
        assertTrue("\"kinds\":[1111]" in c && "\"#A\":[\"${ref.coordinate}\"]" in c, c)
        assertTrue("\"kinds\":[1]" in n && "\"#a\":[\"${ref.coordinate}\"]" in n, n)
    }

    @Test
    fun `stored replies come first, comments and old notes together oldest first`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val c = comment("one"); val o = oldReply("two"); val n = comment("three", parent = c)
                listOf(n, unrelated("x"), o, c).forEach { e.save(it) }
                val first = NostrArticleRepliesRepository(e).observe(ref).first()
                assertEquals(listOf("one", "two", "three"), first.map { it.content })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `the relays are asked with both filters and their replies arrive after the local ones`() = runTest {
        val remote = listOf(comment("remote comment"), oldReply("remote note"))
        fun answer(req: String): List<String> = remote.filter { ev ->
            val j = NoteMapper.decoded(ev)
            (j.kind == 1111 && "\"#A\"" in req && "\"kinds\":[1111]" in req) || (j.kind == 1 && "\"#a\"" in req && "\"kinds\":[1]" in req)
        }.map { it.asJson() }
        SilentWsRelay(recordRequests = true, serveFor = ::answer).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val views = NostrArticleRepliesRepository(e).observe(ref).untilFirst { v -> v.size == 2 }
                assertTrue(views.first().isEmpty(), "the first emission is the (empty) database")
                assertEquals(setOf("remote comment", "remote note"), views.last().map { it.content }.toSet())
                assertTrue(relay.requests.any { "\"#A\"" in it }, relay.requests.toString())
                assertTrue(relay.requests.any { "\"#a\"" in it && "\"#A\"" !in it }, relay.requests.toString())
            } finally {
                e.close()
            }
        }
    }

    /** Runs the flow for a while on real time and reports whether the hint relay got a request. */
    private suspend fun hintAsked(allowForeign: Boolean): Boolean {
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay(answerEose = true, recordRequests = true).use { hint ->
                val e = connectedEngine(dir, own)
                try {
                    val r = NostrArticleRepliesRepository(e, relayFilter = { true }, windowMs = 10)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        kotlinx.coroutines.withTimeoutOrNull(3_000) { r.observe(ref.copy(relays = listOf(hint.url)), allowForeign).collect { } }
                    }
                    return hint.requests.isNotEmpty()
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a hint relay is asked only when foreign relays are allowed`() = runTest {
        assertTrue(hintAsked(allowForeign = true), "the hint relay should be asked")
    }

    @Test
    fun `without foreign relays no hint is contacted`() = runTest {
        assertEquals(false, hintAsked(allowForeign = false))
    }
}
