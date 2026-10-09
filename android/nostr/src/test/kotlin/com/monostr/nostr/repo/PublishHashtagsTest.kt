package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import java.nio.file.Path

/** Hashtags in a note, reply or quote become `t` tags, once each (v0.13.5). */
class PublishHashtagsTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")

    @Test
    fun `post, reply and quote carry one t tag per hashtag`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val target = EventBuilder.textNote("from alice").signWithKeys(alice).also { e.save(it) }.let { NoteMapper.note(it)!! }
        val repo = NostrPublishRepository(e)
        val text = "Tips in #Monero on #nostr, #monero again"
        val ids = listOf(repo.post(text), repo.reply(text, target), repo.quote(text, target)).map { it.eventId }
        for (id in ids) {
            val t = e.eventById(id)!!.tags().toVec().map { it.asVec() }.filter { it.firstOrNull() == "t" }
            assertEquals(listOf(listOf("t", "monero"), listOf("t", "nostr")), t, "event $id")
        }
        e.close()
    }
}
