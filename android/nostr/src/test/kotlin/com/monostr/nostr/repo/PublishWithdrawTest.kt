package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import java.nio.file.Path

/** Unlike and undo repost (2026-10-11): a NIP-09 request naming every own reaction or repost of the note. */
class PublishWithdrawTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()

    @Test
    fun `the request names the own reactions of the note with their kind, and nothing else`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val note = EventBuilder.textNote("note").signWithKeys(alice).also { e.save(it) }
        val other = EventBuilder.textNote("other").signWithKeys(alice).also { e.save(it) }
        val like = EventBuilder.reaction(note, "+").signWithKeys(me).also { e.save(it) }
        val fire = EventBuilder.reaction(note, "🔥").signWithKeys(me).also { e.save(it) }
        EventBuilder.reaction(other, "+").signWithKeys(me).also { e.save(it) }
        EventBuilder.reaction(note, "+").signWithKeys(alice).also { e.save(it) }
        val repo = NostrPublishRepository(e)
        val tags = repo.withdrawTags(NoteMapper.note(note)!!.id, 7)!!
        assertEquals(setOf(like.id().toHex(), fire.id().toHex()), tags.filter { it[0] == "e" }.map { it[1] }.toSet())
        assertEquals(listOf(listOf("k", "7")), tags.filter { it[0] == "k" })
        // no repost to withdraw: nothing to send
        assertNull(repo.withdrawTags(note.id().toHex(), 6))
        e.close()
    }
}
