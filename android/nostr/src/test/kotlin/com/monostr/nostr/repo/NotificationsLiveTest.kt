package com.monostr.nostr.repo

import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.NoteMapper
import com.monostr.tips.event.Event as JsonEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Tag
import java.nio.file.Path

/** Spec perf 3.6: the live filter looks at the kind and the event's JSON in Kotlin instead of every tag through rust. */
class NotificationsLiveTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val meHex = me.publicKey().toHex()

    @Test
    fun `a p tag naming the user matches, also with a relay hint, but text, other p tags and other kinds do not`() {
        val mine = EventBuilder.textNote("mine").signWithKeys(me)
        fun matches(e: rust.nostr.sdk.Event) = LiveMatch.matches(NoteMapper.decoded(e), meHex)
        assertTrue(matches(EventBuilder.reaction(mine, "+").signWithKeys(alice)))
        assertTrue(matches(EventBuilder.textNote("hi").tags(listOf(Tag.parse(listOf("p", meHex, "wss://relay.example")))).signWithKeys(alice)))
        // a tag spelled inside the content is text, not a tag
        assertFalse(matches(EventBuilder.textNote("look [\"p\",\"$meHex\"]").signWithKeys(alice)))
        // the pubkey only mentioned in the content
        assertFalse(matches(EventBuilder.textNote("hello $meHex").signWithKeys(alice)))
        assertFalse(matches(EventBuilder.textNote("hi").tags(listOf(Tag.publicKey(alice.publicKey()))).signWithKeys(alice)))
        // a contact list naming the user is no notification
        assertFalse(matches(EventBuilder.contactList(listOf(Contact(me.publicKey(), null, null))).signWithKeys(alice)))
    }

    @Test
    fun `only a p tag naming exactly the user counts`() {
        fun event(vararg tags: List<String>) = JsonEvent("0".repeat(64), alice.publicKey().toHex(), 1, 1, tags.toList(), "", "0".repeat(128))
        assertTrue(LiveMatch.matches(event(listOf("e", "x"), listOf("p", meHex)), meHex))
        assertTrue(LiveMatch.matches(event(listOf("p", meHex, "wss://r")), meHex))
        // a longer key sharing the prefix, another key, an e tag, a bare p
        assertFalse(LiveMatch.matches(event(listOf("p", "${meHex}00")), meHex))
        assertFalse(LiveMatch.matches(event(listOf("p", alice.publicKey().toHex())), meHex))
        assertFalse(LiveMatch.matches(event(listOf("e", meHex)), meHex))
        assertFalse(LiveMatch.matches(event(listOf("p")), meHex))
        assertFalse(LiveMatch.matches(event(), meHex))
        // backlog 31: a NIP-22 comment naming the user (Mostr bridges Fediverse replies as kind 1111)
        val comment = JsonEvent("0".repeat(64), alice.publicKey().toHex(), 1, 1111, listOf(listOf("e", "x"), listOf("p", meHex)), "", "0".repeat(128))
        assertTrue(LiveMatch.matches(comment, meHex))
    }

    @Test
    fun `incoming subscribes from now on and tells about a live event that names the user`() = runTest {
        val mine = EventBuilder.textNote("my note").signWithKeys(me)
        val like = EventBuilder.reaction(mine, "+").signWithKeys(alice)
        SilentWsRelay(serve = listOf(like.asJson()), recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay, me.secretKey().toHex())
            try {
                val repo = NostrNotificationsRepository(e, NostrTipsRepository(e))
                val got = withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { repo.incoming().first() } }
                assertNotNull(got, "the live like never came through")
                assertTrue(relay.requests.any { "\"since\"" in it && meHex in it }, "requests: ${relay.requests}")
            } finally {
                e.close()
            }
        }
    }
}
