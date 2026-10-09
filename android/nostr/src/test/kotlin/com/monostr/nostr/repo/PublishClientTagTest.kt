package com.monostr.nostr.repo

import com.monostr.nostr.ClientTag
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import java.nio.file.Path

/** NIP-89: notes, replies, quotes, likes and reposts say "via Monostr" when the setting is on; nothing else does. */
class PublishClientTagTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
    private suspend fun clientTags(e: NostrEngine, id: String) = e.eventById(id)!!.tags().toVec().map { it.asVec() }.filter { it.firstOrNull() == "client" }
    private suspend fun other(e: NostrEngine) = EventBuilder.textNote("from alice").signWithKeys(alice).also { e.save(it) }.let { NoteMapper.note(it)!! }

    @Test
    fun `the client tag names Monostr, the handler address and the home relay`() {
        assertEquals(listOf("client", "monostr", "31990:1601fcacdf227ddda10e33e71917930b49be436b43bdd030b799948a9f274ed3:monostr", "wss://relay.monostr.com"), ClientTag.value)
    }

    @Test
    fun `post, reply, quote, like and repost carry exactly one client tag when enabled`() = runTest {
        val e = engine()
        val repo = NostrPublishRepository(e, clientTag = { true })
        val target = other(e)
        val ids = listOf(repo.post("hi"), repo.reply("re", target), repo.quote("q", target), repo.like(target), repo.repost(target)).map { it.eventId }
        for (id in ids) assertEquals(listOf(ClientTag.value), clientTags(e, id), "event $id")
        e.close()
    }

    @Test
    fun `nothing carries a client tag when disabled, which is the constructor default`() = runTest {
        val e = engine()
        val target = other(e)
        val off = NostrPublishRepository(e, clientTag = { false })
        val default = NostrPublishRepository(e)
        for (repo in listOf(off, default)) {
            val ids = listOf(repo.post("hi"), repo.reply("re", target), repo.like(target), repo.repost(target)).map { it.eventId }
            for (id in ids) assertTrue(clientTags(e, id).isEmpty(), "event $id")
        }
        e.close()
    }
}
