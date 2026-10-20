package com.monostr.nostr.repo

import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.connectedEngine
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
        assertTrue(LiveMatch.matches(EventBuilder.reaction(mine, "+").signWithKeys(alice), meHex))
        assertTrue(LiveMatch.matches(EventBuilder.textNote("hi").tags(listOf(Tag.parse(listOf("p", meHex, "wss://relay.example")))).signWithKeys(alice), meHex))
        // a quote inside the content is escaped in the JSON: no tag
        assertFalse(LiveMatch.matches(EventBuilder.textNote("look [\"p\",\"$meHex\"]").signWithKeys(alice), meHex))
        // the pubkey only mentioned in the content
        assertFalse(LiveMatch.matches(EventBuilder.textNote("hello $meHex").signWithKeys(alice), meHex))
        assertFalse(LiveMatch.matches(EventBuilder.textNote("hi").tags(listOf(Tag.publicKey(alice.publicKey()))).signWithKeys(alice), meHex))
        // a contact list naming the user is no notification
        assertFalse(LiveMatch.matches(EventBuilder.contactList(listOf(Contact(me.publicKey(), null, null))).signWithKeys(alice), meHex))
    }

    @Test
    fun `namesUser reads the compact tag form only`() {
        assertTrue(LiveMatch.namesUser("""{"tags":[["e","x"],["p","$meHex"]]}""", meHex))
        assertTrue(LiveMatch.namesUser("""{"tags":[["p","$meHex","wss://r"]]}""", meHex))
        // whitespace variants are not what rust-nostr writes: no match (never a false positive)
        assertFalse(LiveMatch.namesUser("""{"tags":[["p", "$meHex"]]}""", meHex))
        assertFalse(LiveMatch.namesUser("""{"tags":[[ "p","$meHex"]]}""", meHex))
        // escaped quotes inside a content string
        assertFalse(LiveMatch.namesUser("""{"content":"[\"p\",\"$meHex\"]","tags":[]}""", meHex))
        // a longer key sharing the prefix, another key, an e tag
        assertFalse(LiveMatch.namesUser("""{"tags":[["p","${meHex}00"]]}""", meHex))
        assertFalse(LiveMatch.namesUser("""{"tags":[["p","${alice.publicKey().toHex()}"]]}""", meHex))
        assertFalse(LiveMatch.namesUser("""{"tags":[["e","$meHex"]]}""", meHex))
        assertFalse(LiveMatch.namesUser("", meHex))
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
