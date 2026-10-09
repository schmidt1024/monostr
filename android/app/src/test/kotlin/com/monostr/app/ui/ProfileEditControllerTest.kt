package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.media.UploadException
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.profile.PictureField
import com.monostr.app.ui.profile.ProfileEditController
import com.monostr.app.ui.profile.ProfileForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditControllerTest {
    private val me = "e".repeat(64)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `the form is prefilled and saving merges onto the stored kind 0`() = runTest {
        val profiles = FakeProfiles().apply { raw = mapOf(me to """{"name":"alice","about":"hi","lud16":"a@wallet.example","website":"https://w.example"}""") }
        val publish = FakePublish()
        val c = ProfileEditController(me, profiles, publish, eager())
        c.start()
        advanceUntilIdle()
        assertEquals("alice", c.state.value.form.name)
        assertFalse(c.state.value.canSave) // nothing changed yet
        c.update { it.copy(name = "Alice 2", about = "") }
        assertTrue(c.state.value.canSave)
        c.save()
        advanceUntilIdle()
        val sent = obj(publish.profileJson.single())
        assertEquals("Alice 2", sent["name"]!!.jsonPrimitive.content)
        assertFalse("about" in sent)
        assertEquals("a@wallet.example", sent["lud16"]!!.jsonPrimitive.content)
        assertEquals("https://w.example", sent["website"]!!.jsonPrimitive.content)
        assertTrue(c.state.value.done)
        assertEquals(listOf(me), profiles.invalidated)
    }

    @Test
    fun `a picked picture is uploaded for its field and its url lands in the form`() = runTest {
        val uploader = FakeMediaUploader()
        val c = ProfileEditController(me, FakeProfiles(), FakePublish(), eager(), uploader)
        c.start()
        advanceUntilIdle()
        c.upload(PictureField.PICTURE, "content://p/1")
        advanceUntilIdle()
        c.upload(PictureField.BANNER, "content://p/2")
        advanceUntilIdle()
        assertEquals(listOf("AVATAR:content://p/1", "BANNER:content://p/2"), uploader.uploads)
        assertEquals("https://media.test/${"1".padStart(64, '0')}.jpg", c.state.value.form.picture)
        assertEquals("https://media.test/${"2".padStart(64, '0')}.jpg", c.state.value.form.banner)
        assertNull(c.state.value.uploading)
        assertNull(c.state.value.uploadError)
        assertTrue(c.state.value.canSave)
    }

    @Test
    fun `saving waits for a running upload, and a failed upload leaves the field as it was`() = runTest {
        val uploader = FakeMediaUploader().apply { gate = CompletableDeferred() }
        val c = ProfileEditController(me, FakeProfiles(), FakePublish(), eager(), uploader)
        c.start()
        advanceUntilIdle()
        c.update { it.copy(name = "new name") }
        assertTrue(c.state.value.canSave)
        c.upload(PictureField.BANNER, "content://p/1")
        advanceUntilIdle()
        assertEquals(PictureField.BANNER, c.state.value.uploading)
        assertFalse(c.state.value.canSave)
        // one upload at a time
        c.upload(PictureField.PICTURE, "content://p/2")
        assertEquals(1, uploader.uploads.size)

        uploader.failure = UploadException.Nsfw()
        uploader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(c.state.value.uploading)
        assertEquals(PictureField.BANNER to uiText(R.string.upload_error_nsfw), c.state.value.uploadError)
        assertEquals("", c.state.value.form.banner)
        assertTrue(c.state.value.canSave)
        // the next attempt clears the old complaint
        uploader.failure = null
        uploader.gate = null
        c.upload(PictureField.BANNER, "content://p/1")
        advanceUntilIdle()
        assertNull(c.state.value.uploadError)
        assertTrue(c.state.value.form.banner.startsWith("https://media.test/"))
    }

    @Test
    fun `urls must be https, blank is fine`() = runTest {
        val c = ProfileEditController(me, FakeProfiles(), FakePublish(), eager())
        c.start()
        advanceUntilIdle()
        c.update { it.copy(banner = "http://x.example/b.jpg") }
        assertTrue(c.state.value.bannerInvalid)
        assertFalse(c.state.value.canSave)
        c.update { it.copy(banner = "https://x.example/b.jpg", picture = "https://x.example/p .jpg") }
        assertFalse(c.state.value.bannerInvalid)
        assertTrue(c.state.value.pictureInvalid)
        c.update { it.copy(picture = "") }
        assertTrue(c.state.value.canSave)
    }

    @Test
    fun `a publish no relay accepted or a failure keeps the form and reports it`() = runTest {
        for (publish in listOf(FakePublish(relaysOk = false), FakePublish(fail = true))) {
            val profiles = FakeProfiles()
            val c = ProfileEditController(me, profiles, publish, eager())
            c.start()
            advanceUntilIdle()
            c.update { it.copy(name = "x") }
            c.save()
            advanceUntilIdle()
            assertEquals(uiText(R.string.profile_save_failed), c.state.value.message)
            assertFalse(c.state.value.done)
            assertEquals("x", c.state.value.form.name)
            assertTrue(c.state.value.canSave) // saving again is possible
            assertTrue(profiles.invalidated.isEmpty())
        }
    }

    @Test
    fun `an invalid stored kind 0 starts empty and is overwritten deliberately`() = runTest {
        val profiles = FakeProfiles().apply { raw = mapOf(me to "not json") }
        val publish = FakePublish()
        val c = ProfileEditController(me, profiles, publish, eager())
        c.start()
        advanceUntilIdle()
        assertEquals(ProfileForm(), c.state.value.form)
        c.update { it.copy(name = "fresh") }
        c.save()
        advanceUntilIdle()
        assertEquals("""{"name":"fresh"}""", publish.profileJson.single())
    }

    @Test
    fun `only the changed fields are merged, untouched ones stay as stored`() = runTest {
        val profiles = FakeProfiles().apply { raw = mapOf(me to """{"name":"alice","picture":"https://x.example/p.jpg","about":"hi"}""") }
        val publish = FakePublish()
        val c = ProfileEditController(me, profiles, publish, eager())
        c.start()
        advanceUntilIdle()
        c.update { it.copy(name = "Alice 2") }
        c.save()
        advanceUntilIdle()
        val sent = obj(publish.profileJson.single())
        assertEquals("Alice 2", sent["name"]!!.jsonPrimitive.content)
        assertEquals("https://x.example/p.jpg", sent["picture"]!!.jsonPrimitive.content)
        assertEquals("hi", sent["about"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clearing a field removes its key, other fields stay`() = runTest {
        val profiles = FakeProfiles().apply { raw = mapOf(me to """{"name":"alice","about":"hi"}""") }
        val publish = FakePublish()
        val c = ProfileEditController(me, profiles, publish, eager())
        c.start()
        advanceUntilIdle()
        c.update { it.copy(about = "") }
        c.save()
        advanceUntilIdle()
        val sent = obj(publish.profileJson.single())
        assertFalse("about" in sent)
        assertEquals("alice", sent["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a profile the load missed is not wiped by saving one field`() = runTest {
        val profiles = FakeProfiles()
        val publish = FakePublish()
        val c = ProfileEditController(me, profiles, publish, eager())
        c.start()
        advanceUntilIdle()
        profiles.raw = mapOf(me to """{"name":"alice","picture":"https://x.example/p.jpg","about":"hi"}""") // arrives after the form opened
        c.update { it.copy(name = "new") }
        c.save()
        advanceUntilIdle()
        val sent = obj(publish.profileJson.single())
        assertEquals("new", sent["name"]!!.jsonPrimitive.content)
        assertEquals("https://x.example/p.jpg", sent["picture"]!!.jsonPrimitive.content)
        assertEquals("hi", sent["about"]!!.jsonPrimitive.content)
        assertFalse(c.state.value.noProfileFound) // the save-time lookup found it after all
    }

    @Test
    fun `no kind 0 at load and at save warns, and saving still publishes only the edited field`() = runTest {
        val profiles = FakeProfiles()
        val publish = FakePublish()
        val c = ProfileEditController(me, profiles, publish, eager())
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.noProfileFound)
        c.update { it.copy(name = "new") }
        c.save()
        advanceUntilIdle()
        assertEquals("""{"name":"new"}""", publish.profileJson.single())
        assertTrue(c.state.value.done)
    }

    @Test
    fun `an old http picture that stays untouched does not block saving another field`() = runTest {
        val profiles = FakeProfiles().apply { raw = mapOf(me to """{"name":"alice","picture":"http://old.example/p.png"}""") }
        val c = ProfileEditController(me, profiles, FakePublish(), eager())
        c.start()
        advanceUntilIdle()
        assertFalse(c.state.value.pictureInvalid)
        c.update { it.copy(name = "Alice") }
        assertTrue(c.state.value.canSave)
        c.update { it.copy(picture = "http://new.example/p.png") }
        assertTrue(c.state.value.pictureInvalid)
        assertFalse(c.state.value.canSave)
    }

    @Test
    fun `saving merges onto the kind 0 fetched right before, not the one loaded`() = runTest {
        val profiles = FakeProfiles().apply { raw = mapOf(me to """{"name":"alice"}""") }
        val publish = FakePublish()
        val c = ProfileEditController(me, profiles, publish, eager())
        c.start()
        advanceUntilIdle()
        profiles.fresh = mapOf(me to """{"name":"alice","about":"written by another client"}""")
        c.update { it.copy(name = "Alice 2") }
        c.save()
        advanceUntilIdle()
        val sent = obj(publish.profileJson.single())
        assertEquals("Alice 2", sent["name"]!!.jsonPrimitive.content)
        assertEquals("written by another client", sent["about"]!!.jsonPrimitive.content)
        assertEquals(listOf(me), profiles.freshCalls)
    }

    @Test
    fun `removing the picture empties the field and the saved profile has none`() = runTest {
        val profiles = FakeProfiles().apply { raw = mapOf(me to """{"name":"alice","picture":"https://media.monostr.com/${"a".repeat(64)}.jpg","banner":"https://b.example/b.png"}""") }
        val publish = FakePublish()
        val removed = ArrayList<List<String>>()
        val c = ProfileEditController(me, profiles, publish, eager(), removePictures = { removed += it })
        c.start(); advanceUntilIdle()
        c.remove(PictureField.PICTURE)
        assertEquals("", c.state.value.form.picture)
        assertTrue(c.state.value.canSave)
        c.save(); advanceUntilIdle()
        val sent = obj(publish.profileJson.single())
        assertFalse("picture" in sent)
        assertEquals("https://b.example/b.png", sent["banner"]!!.jsonPrimitive.content)
        assertEquals(listOf(listOf("https://media.monostr.com/${"a".repeat(64)}.jpg")), removed)
    }

    @Test
    fun `a replaced picture is handed over too, one moved to the other field is not`() = runTest {
        val old = "https://media.monostr.com/${"a".repeat(64)}.jpg"
        val profiles = FakeProfiles().apply { raw = mapOf(me to """{"picture":"$old","banner":"https://media.monostr.com/${"b".repeat(64)}.jpg"}""") }
        val removed = ArrayList<List<String>>()
        val c = ProfileEditController(me, profiles, FakePublish(), eager(), removePictures = { removed += it })
        c.start(); advanceUntilIdle()
        c.update { it.copy(picture = "https://media.monostr.com/${"c".repeat(64)}.jpg", banner = old) }
        c.save(); advanceUntilIdle()
        assertEquals(listOf(listOf("https://media.monostr.com/${"b".repeat(64)}.jpg")), removed)
    }

    @Test
    fun `nothing is removed from the server when saving fails`() = runTest {
        val profiles = FakeProfiles().apply { raw = mapOf(me to """{"picture":"https://media.monostr.com/${"a".repeat(64)}.jpg"}""") }
        val removed = ArrayList<List<String>>()
        for (publish in listOf(FakePublish(relaysOk = false), FakePublish(fail = true))) {
            val c = ProfileEditController(me, profiles, publish, eager(), removePictures = { removed += it })
            c.start(); advanceUntilIdle()
            c.remove(PictureField.PICTURE)
            c.save(); advanceUntilIdle()
            assertFalse(c.state.value.done)
        }
        assertTrue(removed.isEmpty())
    }
}
