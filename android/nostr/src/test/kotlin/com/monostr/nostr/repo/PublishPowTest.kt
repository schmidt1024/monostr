package com.monostr.nostr.repo

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

/** NIP-13 (2026-10-11): with a difficulty set, notes, replies and quotes carry a nonce and an id with that many leading zero bits. */
class PublishPowTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()

    private fun leadingZeroBits(hex: String): Int {
        var bits = 0
        for (c in hex) {
            val v = c.digitToInt(16)
            if (v == 0) { bits += 4; continue }
            return bits + Integer.numberOfLeadingZeros(v) - 28
        }
        return bits
    }

    @Test
    fun `post, reply and quote are mined to the difficulty, likes and an unset difficulty are not`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        val target = EventBuilder.textNote("from alice").signWithKeys(alice).also { e.save(it) }.let { NoteMapper.note(it)!! }
        val repo = NostrPublishRepository(e, powBits = { 10 })
        for (id in listOf(repo.post("hi #pow"), repo.reply("re", target), repo.quote("q", target)).map { it.eventId }) {
            assertTrue(leadingZeroBits(id) >= 10, "id $id")
            val nonce = e.eventById(id)!!.tags().toVec().map { it.asVec() }.single { it[0] == "nonce" }
            assertEquals("10", nonce[2])
        }
        val like = repo.like(target).eventId
        assertTrue(e.eventById(like)!!.tags().toVec().none { it.asVec()[0] == "nonce" })
        val plain = NostrPublishRepository(e).post("no pow").eventId
        assertTrue(e.eventById(plain)!!.tags().toVec().none { it.asVec()[0] == "nonce" })
        e.close()
    }
}
