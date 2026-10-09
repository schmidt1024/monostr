package com.monostr.app.media

import com.monostr.app.ui.note
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OwnPicturesTest {
    private val server = "https://media.monostr.com"
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private fun url(hash: String, host: String = server) = "$host/$hash.jpg"
    private fun withPictures(id: Char, vararg urls: String): Note =
        note(id, content = "text\n" + urls.joinToString("\n")).copy(media = urls.map { NoteMedia(it, NoteMedia.Kind.IMAGE) })

    private class Rig(
        notes: suspend () -> List<Note> = { emptyList() },
        profile: suspend () -> Map<String, String>? = { emptyMap() },
        val fail: Set<String> = emptySet(),
    ) {
        val deleted = ArrayList<Pair<String, String>>()
        val pictures = OwnPictures(
            MutableStateFlow("https://media.monostr.com"), notes, profile,
            delete = { s, h -> if (h in fail) throw UploadException.Unavailable() else deleted += s to h },
        )
    }

    @Test
    fun `the hash is read only from addresses on the configured server`() {
        assertEquals(a, OwnPictures.sha256Of(url(a), server))
        assertEquals(a, OwnPictures.sha256Of("$server/$a", "$server/"))
        assertNull(OwnPictures.sha256Of(url(a, "https://blossom.primal.net"), server))
        assertNull(OwnPictures.sha256Of("$server/thumbs/$a.jpg", server))
        assertNull(OwnPictures.sha256Of("$server/${"a".repeat(63)}.jpg", server))
        assertNull(OwnPictures.sha256Of("$server/$a.jpg?x=1", server))
        assertNull(OwnPictures.sha256Of("https://media.monostr.com.evil.example/$a.jpg", server))
    }

    @Test
    fun `the pictures of a deleted note are removed from the own server only`() = runTest {
        val n = withPictures('1', url(a), url(b, "https://blossom.primal.net"), "$server/not-a-hash.jpg")
        val r = Rig()
        assertEquals(listOf(url(a)), r.pictures.candidates(n))
        assertEquals(0, r.pictures.remove(r.pictures.candidates(n), exceptNoteId = n.id))
        assertEquals(listOf(server to a), r.deleted)
    }

    @Test
    fun `a picture another own note still shows stays`() = runTest {
        val n = withPictures('1', url(a), url(b))
        val r = Rig(notes = { listOf(n, withPictures('2', "$server/$a.png")) })
        r.pictures.remove(r.pictures.candidates(n), exceptNoteId = n.id)
        assertEquals(listOf(server to b), r.deleted) // the note itself does not protect its pictures
    }

    @Test
    fun `a picture that is the profile picture or banner stays`() = runTest {
        val n = withPictures('1', url(a), url(b))
        val r = Rig(profile = { mapOf("picture" to url(a), "banner" to "$server/$b.png") })
        r.pictures.remove(r.pictures.candidates(n), exceptNoteId = n.id)
        assertEquals(emptyList<Pair<String, String>>(), r.deleted)
    }

    @Test
    fun `failures are counted, the other pictures are still removed`() = runTest {
        val n = withPictures('1', url(a), url(b))
        val r = Rig(fail = setOf(a))
        assertEquals(1, r.pictures.remove(r.pictures.candidates(n), exceptNoteId = n.id))
        assertEquals(listOf(server to b), r.deleted)
    }

    @Test
    fun `a lookup that cannot answer protects every picture`() = runTest {
        val n = withPictures('1', url(a), url(b))
        val unknownProfile = Rig(profile = { null })
        assertEquals(2, unknownProfile.pictures.remove(unknownProfile.pictures.candidates(n), exceptNoteId = n.id))
        val brokenProfile = Rig(profile = { throw UploadException.Unavailable() })
        assertEquals(2, brokenProfile.pictures.remove(brokenProfile.pictures.candidates(n), exceptNoteId = n.id))
        val brokenNotes = Rig(notes = { throw IllegalStateException("db") })
        assertEquals(2, brokenNotes.pictures.remove(brokenNotes.pictures.candidates(n), exceptNoteId = n.id))
        assertEquals(emptyList<Pair<String, String>>(), unknownProfile.deleted + brokenProfile.deleted + brokenNotes.deleted)
    }

    @Test
    fun `a note naming the blob in upper case still protects it`() = runTest {
        val n = withPictures('1', url(a))
        val r = Rig(notes = { listOf(withPictures('2', "$server/${a.uppercase()}.png")) })
        r.pictures.remove(r.pictures.candidates(n), exceptNoteId = n.id)
        assertEquals(emptyList<Pair<String, String>>(), r.deleted)
    }
}
