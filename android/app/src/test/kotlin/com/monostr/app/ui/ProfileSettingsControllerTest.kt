package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.R

import com.monostr.app.ui.feed.target
import com.monostr.app.ui.profile.ProfileController
import com.monostr.app.ui.settings.SettingsController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.FeedRepository
import com.monostr.nostr.repo.ProfileSection
import com.monostr.nostr.repo.FollowCounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSettingsControllerTest {
    private val alice = "a".repeat(64)
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", "https://x/a.png", null, null)))

    /** Controllers get an eager scope on the test scheduler; long-lived collectors stay suspended and are dropped with the scope. */
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 }

    @Test
    fun `profile loads metadata and the author's notes`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(alice to listOf(note('2', alice, 200), note('1', alice, 100))) }
        val c = ProfileController(alice, alice, feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), FakeAuthorRelays(), { false }, eager())
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.isSelf)
        assertEquals("Alice", c.state.value.profile.shownName)
        assertEquals(listOf(200L, 100L), c.state.value.notes.map { it.note.createdAt })
        assertFalse(c.state.value.loading)
        c.bookmark(c.state.value.notes[0].target); advanceUntilIdle()
        assertTrue(c.state.value.notes[0].bookmarked)
    }

    @Test
    fun `profile start survives a repository failure`() = runTest {
        val c = ProfileController(alice, alice, FailingFeed(), profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), FakeAuthorRelays(), { false }, eager())
        c.start()
        advanceUntilIdle()
        assertFalse(c.state.value.loading)
        assertNotNull(c.state.value.message)
    }

    @Test
    fun `follower and following counts arrive step by step, a failing lookup leaves them out`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(alice to listOf(note('1', alice, 100))) }
        val counts = FakeFollowCounts(mapOf(alice to listOf(FollowCounts(12, null), FollowCounts(12, 1683), FollowCounts(12, 3373))))
        val c = ProfileController(alice, alice, feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), FakeAuthorRelays(), { false }, eager(), followCounts = counts)
        c.start()
        advanceUntilIdle()
        assertEquals(FollowCounts(12, 3373), c.state.value.followCounts)
        assertEquals(listOf(alice), counts.asked)

        val failing = FakeFollowCounts(fail = true)
        val d = ProfileController(alice, alice, feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), FakeAuthorRelays(), { false }, eager(), followCounts = failing)
        d.start()
        advanceUntilIdle()
        assertNull(d.state.value.followCounts)
        assertFalse(d.state.value.loading)
        assertNull(d.state.value.message)

        val unknown = ProfileController(alice, alice, feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), FakeAuthorRelays(), { false }, eager(), followCounts = FakeFollowCounts())
        unknown.start()
        advanceUntilIdle()
        assertNull(unknown.state.value.followCounts) // nothing known: no line
    }

    @Test
    fun `profile like failure surfaces a message`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(alice to listOf(note('1', alice, 100))) }
        val c = ProfileController(alice, alice, feed, profiles, FakePublish(fail = true), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), FakeAuthorRelays(), { false }, eager())
        c.start()
        advanceUntilIdle()
        c.like(c.state.value.notes[0].note)
        advanceUntilIdle()
        assertEquals(uiText(R.string.error_send_failed), c.state.value.message?.text)
        assertFalse(c.state.value.notes[0].liked)
    }

    @Test
    fun `profile like success marks the note and reports it`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(alice to listOf(note('1', alice, 100))) }
        val c = ProfileController(alice, alice, feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), FakeAuthorRelays(), { false }, eager())
        c.start()
        advanceUntilIdle()
        c.repost(c.state.value.notes[0].note)
        advanceUntilIdle()
        assertEquals(uiText(R.string.action_repost_sent), c.state.value.message?.text)
        assertTrue(c.state.value.notes[0].reposted)
    }

    @Test
    fun `settings keeps the relay list when applying fails`() = runTest {
        val store = FakeRelayStore(listOf("wss://a.example", "wss://b.example"))
        val c = SettingsController(store, { throw IllegalStateException("io") }, eager())
        c.start()
        advanceUntilIdle()
        assertTrue(c.add("wss://c.example"))
        advanceUntilIdle()
        assertEquals(uiText(R.string.settings_relay_save_failed), c.state.value.error)
        assertEquals(listOf("wss://a.example", "wss://b.example"), c.state.value.relays)
        c.remove("wss://a.example")
        advanceUntilIdle()
        assertEquals(uiText(R.string.settings_relay_save_failed), c.state.value.error)
        assertEquals(listOf("wss://a.example", "wss://b.example"), c.state.value.relays)
    }

    @Test
    fun `settings validates relay urls and keeps at least one`() = runTest {
        val store = FakeRelayStore(listOf("wss://a.example"))
        val applied = ArrayList<List<String>>()
        val c = SettingsController(store, { applied += it; store.set(it) }, eager())
        c.start()
        advanceUntilIdle()
        assertFalse(c.add("http://nope"))
        assertEquals(uiText(R.string.settings_relay_invalid), c.state.value.error)
        assertTrue(c.add("wss://b.example/"))
        advanceUntilIdle()
        assertEquals(listOf(listOf("wss://a.example", "wss://b.example")), applied)
        assertNull(c.state.value.error)
        c.add("wss://b.example")
        advanceUntilIdle()
        assertEquals(1, applied.size)
        c.remove("wss://a.example")
        advanceUntilIdle()
        assertEquals(listOf("wss://b.example"), c.state.value.relays)
        c.remove("wss://b.example")
        assertEquals(uiText(R.string.settings_relay_keep_one), c.state.value.error)
    }
}

/** Simulates a relay/database failure surfacing from `notesBy`. */
private class FailingFeed : FeedRepository {
    override suspend fun follows(): List<String> = emptyList()
    override suspend fun followsLocal(): List<String> = emptyList()
    override suspend fun notes(limit: Int, until: Long?): List<Note> = emptyList()
    override suspend fun refresh(limit: Int) {}
    override suspend fun loadMore(before: Long, limit: Int): List<Note> = emptyList()
    override fun live(limit: Int): Flow<List<Note>> = throw NotImplementedError()
    override suspend fun notesBy(author: String, limit: Int, fetch: Boolean): List<Note> = throw IllegalStateException("boom")
    override suspend fun profileNotes(author: String, section: ProfileSection, limit: Int, fetch: Boolean): List<Note> = throw IllegalStateException("boom")
    override suspend fun moreProfileNotes(author: String, section: ProfileSection, before: Long, limit: Int): List<Note> = throw IllegalStateException("boom")
}
