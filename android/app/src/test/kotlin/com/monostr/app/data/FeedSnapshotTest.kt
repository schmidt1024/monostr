package com.monostr.app.data

import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FeedSnapshotTest {
    @TempDir lateinit var dir: File

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private fun note(c: Char, author: String, at: Long, repostOf: Note? = null) = Note(
        c.toString().repeat(64), author, "hi $c", at, if (repostOf != null) 6 else 1, null, null, repostOf, emptyList(),
        media = listOf(NoteMedia("https://x.example/p.jpg", NoteMedia.Kind.IMAGE, blurhash = "LNM", dim = 400 to 300)),
        hashtags = listOf("monero"),
    )
    private val aliceProfile = Profile(alice, "alice", "Alice", "https://x.example/a.png", null, null)

    @Test
    fun `items come back as written, at most the first thirty`() = runTest {
        val snap = FeedSnapshot(File(dir, "s.json"))
        val inner = note('2', bob, 900)
        val items = listOf(FeedSnapshot.Item(note('1', alice, 1000, repostOf = inner), aliceProfile, Profile.empty(bob))) +
            (0 until 40).map { FeedSnapshot.Item(note('c', alice, 800L - it), aliceProfile, aliceProfile) }
        snap.write(items)
        val back = snap.read()
        assertEquals(FeedSnapshot.MAX_ITEMS, back.size)
        assertEquals(items.first(), back.first())
    }

    @Test
    fun `a write after delete (logout) leaves no file`() = runTest {
        val file = File(dir, "s.json")
        val snap = FeedSnapshot(file)
        snap.delete()
        snap.write(listOf(FeedSnapshot.Item(note('1', alice, 1000), aliceProfile, aliceProfile)))
        assertFalse(file.exists())
    }

    @Test
    fun `a missing or broken file reads as empty, delete removes it`() = runTest {
        val file = File(dir, "s.json")
        val snap = FeedSnapshot(file)
        assertTrue(snap.read().isEmpty())
        file.writeText("{not json")
        assertTrue(snap.read().isEmpty())
        snap.write(listOf(FeedSnapshot.Item(note('1', alice, 1000), aliceProfile, aliceProfile)))
        snap.delete()
        assertFalse(file.exists())
    }
}
