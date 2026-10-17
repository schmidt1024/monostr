package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.app.media.UploadedMedia
import com.monostr.app.ui.FakeMediaUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec 2026-10-16 §8.1: one draft per account and target, JSON with the finished uploads; 30-day expiry. */
class DraftStoreTest {
    @TempDir lateinit var dir: Path
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val note1 = "1".repeat(64)
    private fun media(n: Int) = UploadedMedia("https://media.test", "https://media.test/$n.jpg", "image/jpeg", n.toString().padStart(64, '0'), 1000, 400, 300, "LNM}7u", fresh = true)
    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("drafts.preferences_pb").toFile() }

    @Test
    fun `a draft is kept per account and target, pictures included`() = runTest {
        val scope = scope()
        val s = PrefsDraftStore(dataStore(scope))
        val d = Draft("hello", sensitive = true, attachments = listOf(media(1)), updatedAt = 100)
        s.save(alice, DraftTargets.NOTE, d)
        s.save(alice, DraftTargets.of(note1, null), Draft("re", updatedAt = 100))
        assertEquals(d, s.load(alice, DraftTargets.NOTE))
        assertEquals("re", s.load(alice, "reply:$note1")?.text)
        assertNull(s.load(alice, DraftTargets.of(null, note1))) // quote:… is another target
        assertNull(s.load(bob, DraftTargets.NOTE))
        s.remove(alice, DraftTargets.NOTE)
        assertNull(s.load(alice, DraftTargets.NOTE))
        assertEquals("re", s.load(alice, "reply:$note1")?.text)
        scope.cancel()
    }

    @Test
    fun `an empty draft removes the key`() = runTest {
        val scope = scope()
        val ds = dataStore(scope)
        val s = PrefsDraftStore(ds)
        s.save(alice, DraftTargets.NOTE, Draft("x", updatedAt = 1))
        s.save(alice, DraftTargets.NOTE, Draft("   ", sensitive = true, updatedAt = 2))
        assertNull(s.load(alice, DraftTargets.NOTE))
        assertTrue(ds.data.first().asMap().isEmpty())
        scope.cancel()
    }

    @Test
    fun `expiry removes drafts of thirty days and more, of this account only`() = runTest {
        val scope = scope()
        val s = PrefsDraftStore(dataStore(scope))
        val now = DraftStore.MAX_AGE_SECONDS + 1_000
        s.save(alice, DraftTargets.NOTE, Draft("old", updatedAt = 1_000)) // exactly 30 days
        s.save(alice, "quote:$note1", Draft("young", updatedAt = now - 10))
        s.save(bob, DraftTargets.NOTE, Draft("bob old", updatedAt = 0))
        assertEquals(listOf("old"), s.expire(alice, now).map { it.text })
        assertEquals(listOf("young"), s.all(alice).map { it.text })
        assertEquals("bob old", s.load(bob, DraftTargets.NOTE)?.text)
        scope.cancel()
    }

    @Test
    fun `an unreadable entry reads as no draft and goes at the next expiry`() = runTest {
        val scope = scope()
        val ds = dataStore(scope)
        val s = PrefsDraftStore(ds)
        ds.edit { it[stringPreferencesKey("draft.$alice.note")] = "{not json" }
        assertNull(s.load(alice, DraftTargets.NOTE))
        assertTrue(s.expire(alice, now = 0).isEmpty())
        assertTrue(ds.data.first().asMap().isEmpty())
        scope.cancel()
    }

    @Test
    fun `expiry deletes the pictures of expired drafts unless a younger draft names them`() = runTest {
        val scope = scope()
        val s = PrefsDraftStore(dataStore(scope))
        val now = DraftStore.MAX_AGE_SECONDS + 10
        s.save(alice, DraftTargets.NOTE, Draft("old", attachments = listOf(media(1), media(2)), updatedAt = 0))
        s.save(alice, "reply:$note1", Draft("young", attachments = listOf(media(2)), updatedAt = now))
        val uploader = FakeMediaUploader()
        expireDrafts(s, alice, now, uploader)
        assertEquals(listOf(media(1).sha256), uploader.discarded)
        assertNull(s.load(alice, DraftTargets.NOTE))
        scope.cancel()
    }

    @Test
    fun `logout removes every draft of the account and deletes their pictures, the other account keeps its own`() = runTest {
        val scope = scope()
        val s = PrefsDraftStore(dataStore(scope))
        s.save(alice, DraftTargets.NOTE, Draft("a", attachments = listOf(media(1), media(2)), updatedAt = 9_999_999_999))
        s.save(alice, "reply:$note1", Draft("b", attachments = listOf(media(2)), updatedAt = 10))
        s.save(bob, DraftTargets.NOTE, Draft("bob's", attachments = listOf(media(3)), updatedAt = 10))
        val uploader = FakeMediaUploader()
        removeDrafts(s, alice, uploader)
        assertTrue(s.all(alice).isEmpty())
        assertEquals(listOf(media(1).sha256, media(2).sha256), uploader.discarded.sorted())
        assertEquals("bob's", s.load(bob, DraftTargets.NOTE)!!.text)
        scope.cancel()
    }
}
