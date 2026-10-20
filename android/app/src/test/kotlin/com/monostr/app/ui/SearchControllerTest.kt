package com.monostr.app.ui

import com.monostr.app.data.PendingTip
import com.monostr.app.R
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.repo.ListOutcome
import com.monostr.nostr.repo.MuteRepository
import com.monostr.app.ui.feed.target
import com.monostr.app.ui.search.SearchController
import com.monostr.app.ui.search.SearchMode
import com.monostr.app.ui.search.SearchNav
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.monostr.app.data.RecentSearchesStore
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.NoteVisibility
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
    private val alice = "a".repeat(64)
    private val aliceProfile = Profile(alice, "alice", "Alice", null, null, null)
    private val profiles = FakeProfiles(mapOf(alice to aliceProfile))
    private val relays = MutableStateFlow(listOf("wss://s.example"))

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.controller(
        search: FakeSearch,
        publish: FakePublish = FakePublish(),
        detachScope: CoroutineScope = eager(),
        muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
        mute: MuteRepository? = null,
        recentSearches: RecentSearchesStore? = null,
        pending: FakePendingTips = FakePendingTips(),
    ): SearchController {
        val scope = eager()
        val decorations = TipDecorations(FakeTips(), pending, profiles, publish, scope) { 5_000 }
        val deletions = NoteDeletions()
        return SearchController(
            search, profiles, publish, decorations, BookmarkActions(FakeBookmarks(), eager()), relays, scope, debounceMs = 400, detachScope = detachScope,
            deletions = deletions, visibility = NoteVisibility(deletions, muted), mute = mute, recentSearches = recentSearches,
        )
    }

    @Test
    fun `remote people show while the notes are still on their way`() = runTest {
        val search = FakeSearch().apply {
            local = listOf(aliceProfile)
            remotePeople = listOf(Profile("b".repeat(64), "alicia", null, null, null, null))
            notesGate = CompletableDeferred()
        }
        val c = controller(search)
        c.start()
        c.onQueryChange("ali"); c.submit(); runCurrent()
        assertEquals(listOf(alice, "b".repeat(64)), c.state.value.people.map { it.pubkey })
        assertTrue(c.state.value.loading)
        search.notesGate!!.complete(Unit); advanceUntilIdle()
        assertFalse(c.state.value.loading)
    }

    @Test
    fun `a hashtag search shows the stored notes before the relays answered`() = runTest {
        val stored = note('1', alice, 1000)
        val fetched = note('2', alice, 2000)
        val search = FakeSearch().apply {
            hashtagLocalNotes = mapOf("monero" to listOf(stored))
            hashtagNotes = mapOf("monero" to listOf(fetched, stored))
            hashtagGate = CompletableDeferred()
        }
        val c = controller(search)
        c.start()
        c.onQueryChange("#monero"); c.submit(); runCurrent()
        assertEquals(listOf(stored.id), c.state.value.notes.map { it.note.id })
        assertTrue(c.state.value.loading)
        search.hashtagGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(fetched.id, stored.id), c.state.value.notes.map { it.note.id })
        assertFalse(c.state.value.loading)
    }

    @Test
    fun `a pending tip set while the hits show is kept when a profile lands`() = runTest {
        val bob = "b".repeat(64)
        val n1 = note('1', alice, 2000); val n2 = note('2', bob, 1500)
        val search = FakeSearch().apply { hashtagNotes = mapOf("monero" to listOf(n1, n2)) }
        val pending = FakePendingTips()
        val c = controller(search, pending = pending)
        c.start()
        c.onQueryChange("#monero"); c.submit(); advanceUntilIdle()
        // the user tipped n1 in the meantime (e.g. from the feed)
        pending.add(PendingTip("e".repeat(64), n1.id, 5, 4_000)); advanceUntilIdle()
        profiles.land(Profile(bob, "bob", "Bob", null, null, null)); advanceUntilIdle()
        assertEquals(listOf(n1.id, n2.id), c.state.value.notes.map { it.note.id })
        assertTrue(c.state.value.notes.first { it.note.id == n1.id }.pendingTip)
        assertEquals("Bob", c.state.value.notes.first { it.note.id == n2.id }.author.shownName)
    }

    @Test
    fun `a sent search is remembered even when the field is cleared before it ran`() = runTest {
        val recent = FakeRecentSearches()
        val search = FakeSearch().apply { localGate = CompletableDeferred() }
        val c = controller(search, recentSearches = recent)
        c.start()
        c.onQueryChange("monero"); c.submit()
        c.onQueryChange(""); advanceUntilIdle() // cleared right away: the search is cancelled before its first read returns
        c.onQueryChange("#nostr"); c.submit()
        c.onQueryChange(""); advanceUntilIdle()
        assertEquals(listOf("#nostr", "monero"), recent.state.value)
    }

    @Test
    fun `text and hashtag searches are remembered, a search that found nothing is still remembered, links are not`() = runTest {
        val recent = FakeRecentSearches()
        val search = FakeSearch()
        val c = controller(search, recentSearches = recent)
        c.start()
        c.onQueryChange("monero"); c.submit(); advanceUntilIdle()
        c.onQueryChange("#nostr"); c.submit(); advanceUntilIdle()
        c.onQueryChange("nothing-here"); c.submit(); advanceUntilIdle() // the fake returns no hits
        c.onQueryChange(alice); c.submit(); advanceUntilIdle() // a profile link opens the profile and is not remembered
        assertEquals(listOf("nothing-here", "#nostr", "monero"), recent.state.value)
        assertEquals(listOf("nothing-here", "#nostr", "monero"), c.state.value.recent)
    }

    @Test
    fun `a typing pause runs the search but remembers nothing, a pasted private key is never remembered`() = runTest {
        val recent = FakeRecentSearches()
        val search = FakeSearch()
        val c = controller(search, recentSearches = recent)
        c.start()
        c.onQueryChange("mon"); advanceTimeBy(500); runCurrent()
        assertTrue(search.calls.any { it.startsWith("local:mon") }) // the pause searched
        assertTrue(recent.state.value.isEmpty()) // …but only a sent search is kept (spec 11.4 §2)
        c.onQueryChange("nsec1" + "q".repeat(58)); c.submit(); advanceUntilIdle()
        assertTrue(recent.state.value.isEmpty())
        c.onQueryChange("header"); c.submit(); advanceUntilIdle() // a word that once clashed with the list's header key
        assertEquals(listOf("header"), recent.state.value)
    }

    @Test
    fun `tapping a recent entry restarts the search once, removing and clearing update the list`() = runTest {
        val recent = FakeRecentSearches().apply { state.value = listOf("#nostr", "monero") }
        val search = FakeSearch()
        val c = controller(search, recentSearches = recent)
        c.start(); advanceUntilIdle()
        assertEquals(listOf("#nostr", "monero"), c.state.value.recent)
        c.searchRecent("monero"); advanceUntilIdle()
        assertEquals("monero", c.state.value.query)
        assertEquals(SearchMode.TEXT, c.state.value.mode)
        assertEquals(listOf("monero", "#nostr"), recent.state.value) // moved up, not duplicated
        assertEquals(1, search.calls.count { it.startsWith("local:monero") }) // one search for one tap
        c.removeRecent("#nostr"); advanceUntilIdle()
        assertEquals(listOf("monero"), c.state.value.recent)
        c.clearRecent(); advanceUntilIdle()
        assertTrue(c.state.value.recent.isEmpty())
    }

    @Test
    fun `a muted account's notes are missing from the result and leave it when muted later`() = runTest {
        val bob = "b".repeat(64)
        val muted = MutableStateFlow(setOf(alice))
        val search = FakeSearch().apply { remoteNotes = listOf(note('1', alice, 2000, "alice rocks"), note('2', bob, 1000, "bob rocks too")) }
        val c = controller(search, muted = muted)
        c.start()
        c.onQueryChange("rocks"); c.submit(); advanceUntilIdle()
        assertEquals(listOf("bob rocks too"), c.state.value.notes.map { it.note.content })
        muted.value = setOf(alice, bob); advanceUntilIdle()
        assertTrue(c.state.value.notes.isEmpty())
    }

    @Test
    fun `a mute whose publish fails runs the search again, so the notes come back`() = runTest {
        val bob = "b".repeat(64)
        val mute = FakeMute(outcome = ListOutcome.PublishFailed).apply { gate = CompletableDeferred() }
        val search = FakeSearch().apply { remoteNotes = listOf(note('1', alice, 2000, "alice rocks"), note('2', bob, 1000, "bob rocks too")) }
        val c = controller(search, muted = mute.muted, mute = mute)
        c.start()
        c.onQueryChange("rocks"); c.submit(); advanceUntilIdle()
        c.mute(note('1', alice, 2000, "alice rocks")); advanceUntilIdle()
        assertEquals(listOf("bob rocks too"), c.state.value.notes.map { it.note.content }) // gone at once
        val searches = search.calls.count { it.startsWith("notes:") }
        mute.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(uiText(R.string.error_send_failed), c.state.value.message?.text)
        assertTrue(search.calls.count { it.startsWith("notes:") } > searches)
        assertEquals(listOf("alice rocks", "bob rocks too"), c.state.value.notes.map { it.note.content })
    }

    @Test
    fun `typing debounces, shows local people at once and merges remote people and notes`() = runTest {
        val search = FakeSearch().apply {
            local = listOf(aliceProfile)
            remotePeople = listOf(Profile("b".repeat(64), "alicia", null, null, null, null))
            remoteNotes = listOf(note('1', alice, 1000, "alice rocks"))
        }
        val c = controller(search)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(listOf("wss://s.example")), search.attached)
        c.onQueryChange("ali")
        advanceTimeBy(399)
        assertTrue(search.calls.none { it.startsWith("people:") })
        advanceTimeBy(2); advanceUntilIdle()
        val s = c.state.value
        assertEquals(SearchMode.TEXT, s.mode)
        assertEquals(listOf(alice, "b".repeat(64)), s.people.map { it.pubkey })
        assertEquals(listOf("alice rocks"), s.notes.map { it.note.content })
        assertFalse(s.loading)
        assertEquals("Alice", s.notes[0].author.shownName)
        c.bookmark(s.notes[0].target); advanceUntilIdle()
        assertTrue(c.state.value.notes[0].bookmarked)
    }

    @Test
    fun `submit searches at once and a relay error keeps local hits with a message`() = runTest {
        val search = FakeSearch().apply { local = listOf(aliceProfile); error = IllegalStateException("relay down") }
        val c = controller(search)
        c.start()
        c.onQueryChange("al"); c.submit(); advanceUntilIdle()
        assertEquals(listOf(alice), c.state.value.people.map { it.pubkey })
        assertNotNull(c.state.value.error)
        assertEquals(1, search.calls.count { it.startsWith("people:") }, "debounce job was cancelled by submit")
    }

    @Test
    fun `npub navigates to the profile and clears the field, note navigates to the thread`() = runTest {
        val keys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
        val search = FakeSearch()
        val c = controller(search)
        c.start()
        val seen = ArrayList<SearchNav>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.nav.collect { seen += it } }
        c.onQueryChange(keys.publicKey().toBech32()); c.submit(); advanceUntilIdle()
        assertEquals(listOf<SearchNav>(SearchNav.Profile(keys.publicKey().toHex())), seen)
        assertEquals("", c.state.value.query)
        c.onQueryChange("nostr:" + rust.nostr.sdk.EventId.parse("e".repeat(64)).toBech32()); c.submit(); advanceUntilIdle()
        assertEquals(SearchNav.Thread("e".repeat(64)), seen.last())
        assertTrue(search.calls.isEmpty())
    }

    @Test
    fun `hashtag uses the normal relays and pages with until`() = runTest {
        val search = FakeSearch().apply { hashtagNotes = mapOf("monero" to listOf(note('2', alice, 2000), note('1', alice, 1000))) }
        val c = controller(search)
        c.start()
        c.onQueryChange("#Monero"); c.submit(); advanceUntilIdle()
        assertEquals(SearchMode.HASHTAG, c.state.value.mode)
        assertEquals("monero", c.state.value.hashtag)
        assertEquals(listOf(2000L, 1000L), c.state.value.notes.map { it.note.createdAt })
        c.loadMore(); advanceUntilIdle()
        assertEquals("tag:monero:1000", search.calls.last())
    }

    @Test
    fun `no second page while the first one is still coming`() = runTest {
        val stored = note('1', alice, 1000)
        val fetched = note('2', alice, 2000)
        val search = FakeSearch().apply {
            hashtagLocalNotes = mapOf("monero" to listOf(stored))
            hashtagNotes = mapOf("monero" to listOf(fetched, stored))
            hashtagGate = CompletableDeferred()
        }
        val c = controller(search)
        c.start()
        c.onQueryChange("#monero"); c.submit(); runCurrent()
        c.loadMore(); runCurrent() // the end-of-list effect fires on the stored notes
        assertFalse(search.calls.any { it == "tag:monero:1000" })
        search.hashtagGate!!.complete(Unit); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        assertEquals("tag:monero:1000", search.calls.last())
    }

    @Test
    fun `without search relays people come from the local database and the hint is set`() = runTest {
        relays.value = emptyList()
        val search = FakeSearch().apply { local = listOf(aliceProfile) }
        val c = controller(search)
        c.start()
        c.onQueryChange("ali"); c.submit(); advanceUntilIdle()
        assertTrue(c.state.value.noSearchRelays)
        assertEquals(listOf(alice), c.state.value.people.map { it.pubkey })
        c.close(); advanceUntilIdle()
        assertEquals(listOf(emptyList<String>()), search.detached)
    }

    @Test
    fun `close still detaches the search relays after the owning scope is cancelled`() = runTest {
        val search = FakeSearch()
        val scope = eager()
        val detachScope = eager()
        val decorations = TipDecorations(FakeTips(), FakePendingTips(), profiles, FakePublish(), scope) { 5_000 }
        val c = SearchController(search, profiles, FakePublish(), decorations, BookmarkActions(FakeBookmarks(), eager()), relays, scope, debounceMs = 400, detachScope = detachScope)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(listOf("wss://s.example")), search.attached)
        // models lifecycle 2.10+: viewModelScope is already cancelled by the time onCleared runs close()
        scope.cancel()
        c.close(); advanceUntilIdle()
        assertEquals(listOf(listOf("wss://s.example")), search.detached)
    }

    @Test
    fun `clearing the query while a search is running cancels it and goes idle`() = runTest {
        val search = FakeSearch().apply { local = listOf(aliceProfile); notesGate = CompletableDeferred() }
        val c = controller(search)
        c.start()
        c.onQueryChange("ali"); c.submit()
        assertEquals(SearchMode.TEXT, c.state.value.mode)
        assertTrue(c.state.value.loading)
        c.onQueryChange("")
        assertEquals(SearchMode.IDLE, c.state.value.mode)
        assertTrue(c.state.value.notes.isEmpty())
        assertFalse(c.state.value.loading)
        // even once the gated call could resolve, the cancelled search must not overwrite the idle state
        search.notesGate?.complete(Unit); advanceUntilIdle()
        assertEquals(SearchMode.IDLE, c.state.value.mode)
        assertTrue(c.state.value.notes.isEmpty())
    }

    @Test
    fun `a relay list change detaches the old relays and attaches the new ones`() = runTest {
        val search = FakeSearch()
        val c = controller(search)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(listOf("wss://s.example")), search.attached)
        relays.value = listOf("wss://new.example")
        advanceUntilIdle()
        assertEquals(listOf(listOf("wss://s.example")), search.detached)
        assertEquals(listOf(listOf("wss://s.example"), listOf("wss://new.example")), search.attached)
    }

    @Test
    fun `the first local people of the screen are marked once`() = runTest {
        withPerfLines {
            val search = FakeSearch().apply { local = listOf(aliceProfile); notesGate = CompletableDeferred() }
            val c = controller(search)
            c.start()
            c.onQueryChange("ali"); c.submit(); runCurrent()
            assertEquals(1, count { it.endsWith(" search people shown") }, "marked before the notes came")
            search.notesGate!!.complete(Unit); advanceUntilIdle()
            c.onQueryChange("alic"); c.submit(); advanceUntilIdle()
            assertEquals(1, count { it.endsWith(" search people shown") })
        }
    }
}
