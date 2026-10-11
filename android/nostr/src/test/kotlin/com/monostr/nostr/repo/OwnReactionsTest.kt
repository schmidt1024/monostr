package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path

/** v0.13.9: the own likes and reposts come from the user's own kind 7 and kind 6 events, not from what one screen remembers. */
class OwnReactionsTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())

    @Test
    fun `likes and reposts of the user count, a dislike, a withdrawn like and someone else's like do not`() = runTest {
        val e = engine()
        val notes = (0 until 5).map { EventBuilder.textNote("note $it").signWithKeys(alice).also { n -> e.save(n) } }
        e.save(EventBuilder.reaction(notes[0], "+").signWithKeys(me))
        e.save(EventBuilder.reaction(notes[1], "-").signWithKeys(me))
        e.save(EventBuilder.repost(notes[2], null).signWithKeys(me))
        val withdrawn = EventBuilder.reaction(notes[3], "🤙").signWithKeys(me).also { e.save(it) }
        e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", withdrawn.id().toHex())))).signWithKeys(me))
        e.save(EventBuilder.reaction(notes[4], "+").signWithKeys(alice))
        val own = NostrOwnReactions(e)
        own.load(fetch = false)
        assertEquals(setOf(notes[0].id().toHex()), own.liked.value)
        assertEquals(setOf(notes[2].id().toHex()), own.reposted.value)
        e.close()
    }

    @Test
    fun `an optimistic mark shows at once and goes again when it is taken back`() = runTest {
        val own = MemoryOwnReactions()
        val id = EventId.parse("a".repeat(64)).toHex()
        assertEquals(true, own.add(7, id))
        assertEquals(false, own.add(7, id))
        assertEquals(setOf(id), own.liked.value)
        own.remove(7, id)
        assertEquals(emptySet<String>(), own.liked.value)
        assertEquals(true, own.add(6, id))
        assertEquals(setOf(id), own.reposted.value)
    }
}
