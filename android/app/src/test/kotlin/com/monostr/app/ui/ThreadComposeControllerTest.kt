package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.R

import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys
import com.monostr.app.ui.compose.ComposeController
import com.monostr.app.ui.compose.MentionSuggester
import com.monostr.nostr.Npub
import kotlinx.coroutines.CompletableDeferred
import com.monostr.app.ui.feed.target
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.NoteVisibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.monostr.app.ui.thread.ThreadController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ThreadRepository
import com.monostr.nostr.repo.ThreadView

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadComposeControllerTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", "https://x/a.png", null, null)))

    /** Controllers get an eager scope on the test scheduler; long-lived collectors stay suspended and are dropped with the scope. */
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 }

    @Test
    fun `thread orders root then replies and marks the focused note`() = runTest {
        val root = note('1', alice, 100)
        val r1 = note('2', bob, 200, replyTo = root.id)
        val r2 = note('3', alice, 300, replyTo = r1.id, root = root.id)
        val threads = FakeThreads(mapOf(r1.id to ThreadView(root, r1, listOf(r1, r2))))
        val c = ThreadController(r1.id, threads, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        assertEquals(listOf(root.id, r1.id, r2.id), c.state.value.items.map { it.note.id })
        assertEquals(r1.id, c.state.value.focusedId)
        assertFalse(c.state.value.notFound)
        c.bookmark(c.state.value.items[0].target); advanceUntilIdle()
        assertTrue(c.state.value.items[0].bookmarked)
        val missing = ThreadController("9".repeat(64), threads, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        missing.start()
        advanceUntilIdle()
        assertTrue(missing.state.value.notFound)
    }

    @Test
    fun `a withdrawn focused note shows the thread as not found`() = runTest {
        val root = note('1', alice, 100)
        val threads = FakeThreads(mapOf(root.id to ThreadView(root, root, listOf(root), gone = true)))
        val c = ThreadController(root.id, threads, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.notFound)
        assertTrue(c.state.value.items.isEmpty())
    }

    @Test
    fun `compose posts or replies and reports relay state`() = runTest {
        val root = note('1', alice, 100)
        val threads = FakeThreads(mapOf(root.id to ThreadView(root, root, emptyList())))
        val publish = FakePublish()
        val post = ComposeController(null, threads, publish, eager())
        post.start()
        post.send("  ")
        assertEquals(uiText(R.string.compose_error_empty), post.state.value.error)
        post.send("hello")
        advanceUntilIdle()
        assertTrue(post.state.value.done)
        assertNull(post.state.value.error)
        assertEquals(listOf("post:hello"), publish.calls)
        val reply = ComposeController(root.id, threads, publish, eager())
        reply.start()
        advanceUntilIdle()
        assertEquals(root.id, reply.state.value.replyTo?.id)
        reply.send("re")
        advanceUntilIdle()
        assertEquals("reply:1111:re", publish.calls.last())
        val offline = ComposeController(null, threads, FakePublish(relaysOk = false), eager())
        offline.send("x")
        advanceUntilIdle()
        assertFalse(offline.state.value.done)
        assertEquals(uiText(R.string.compose_notice_offline), offline.state.value.error)
    }

    @Test
    fun `compose quotes a note, an empty text is allowed`() = runTest {
        val quoted = note('1', alice, 100)
        val publish = FakePublish()
        val c = ComposeController(null, FakeThreads(emptyMap(), mapOf(quoted.id to quoted)), publish, eager(), quoteId = quoted.id)
        c.start()
        advanceUntilIdle()
        assertEquals(quoted.id, c.state.value.quoted?.id)
        c.send("")
        advanceUntilIdle()
        assertEquals(listOf("quote:1111:"), publish.calls)
        assertTrue(c.state.value.done)
    }

    @Test
    fun `typing @ suggests follows and local profiles without network, picking inserts the npub`() = runTest {
        val alina = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002").publicKey().toHex()
        val alfred = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003").publicKey().toHex()
        val feed = FakeFeed().apply { followList = listOf(alina) }
        val known = FakeProfiles(mapOf(alina to Profile(alina, "alina", "Alina", null, null, null)))
        val search = FakeSearch().apply { local = listOf(Profile(alfred, "alfred", null, null, null, null)) }
        val c = ComposeController(null, FakeThreads(emptyMap()), FakePublish(), eager(), suggester = MentionSuggester(feed, known, search, self = "e".repeat(64)))
        c.start()
        advanceUntilIdle()
        val text = "hi @al"
        c.onTextChange(text, text.length)
        advanceUntilIdle()
        assertEquals(listOf(alina, alfred), c.state.value.suggestions.map { it.pubkey })
        assertTrue(known.prefetched.isEmpty()) // names come from the database only, nothing was fetched
        val edit = c.pick(text, text.length, c.state.value.suggestions[0])!!
        assertEquals("hi nostr:${Npub.encode(alina)} ", edit.text)
        assertEquals(edit.text.length, edit.cursor)
        assertEquals(mapOf(alina to "Alina"), c.state.value.mentionNames)
        assertTrue(c.state.value.suggestions.isEmpty())
        c.onTextChange("no mention", 10)
        advanceUntilIdle()
        assertTrue(c.state.value.suggestions.isEmpty())
    }

    @Test
    fun `a send tapped while the quote loads shows its error, which the loaded quote clears`() = runTest {
        val quoted = note('1', alice, 100)
        val gate = CompletableDeferred<Note?>()
        val threads = object : com.monostr.nostr.repo.ThreadRepository {
            override fun observe(noteId: String): Flow<ThreadView> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun note(id: String) = gate.await()
        }
        val c = ComposeController(null, threads, FakePublish(), eager(), quoteId = quoted.id)
        c.start()
        c.send("text")
        assertEquals(uiText(R.string.quote_missing), c.state.value.error)
        gate.complete(quoted)
        advanceUntilIdle()
        assertEquals(quoted.id, c.state.value.quoted?.id)
        assertNull(c.state.value.error)
    }

    @Test
    fun `compose with an unknown quote target never posts`() = runTest {
        val publish = FakePublish()
        val c = ComposeController(null, FakeThreads(emptyMap()), publish, eager(), quoteId = "9".repeat(64))
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.quoteUnresolved)
        c.send("text")
        advanceUntilIdle()
        assertTrue(publish.calls.isEmpty())
        assertEquals(uiText(R.string.quote_missing), c.state.value.error)
    }

    @Test
    fun `compose with unresolvable reply target never posts a root note`() = runTest {
        val threads = FakeThreads(emptyMap())
        val publish = FakePublish()
        val c = ComposeController(replyToId = "9".repeat(64), threads, publish, eager())
        c.start()
        advanceUntilIdle()
        c.send("hi")
        advanceUntilIdle()
        assertTrue(publish.calls.isEmpty())
        assertTrue(c.state.value.error != null)
        assertFalse(c.state.value.done)
    }

    @Test
    fun `thread start survives a repository failure`() = runTest {
        val threads = FailingThreads()
        val c = ThreadController("9".repeat(64), threads, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.notFound)
        assertFalse(c.state.value.loading)
    }

    @Test
    fun `thread like failure surfaces a message`() = runTest {
        val root = note('1', alice, 100)
        val threads = FakeThreads(mapOf(root.id to ThreadView(root, root, emptyList())))
        val c = ThreadController(root.id, threads, profiles, FakePublish(fail = true), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        c.like(c.state.value.items[0].note)
        advanceUntilIdle()
        assertEquals(uiText(R.string.error_send_failed), c.state.value.message?.text)
        assertFalse(c.state.value.items[0].liked)
    }

    @Test
    fun `thread like success marks the note and reports it`() = runTest {
        val root = note('1', alice, 100)
        val threads = FakeThreads(mapOf(root.id to ThreadView(root, root, emptyList())))
        val c = ThreadController(root.id, threads, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        c.like(c.state.value.items[0].note)
        advanceUntilIdle()
        assertEquals(uiText(R.string.action_like_sent), c.state.value.message?.text)
        assertTrue(c.state.value.items[0].liked)
    }

    @Test
    fun `sending again after an offline send resends the stored event instead of posting twice`() = runTest {
        val publish = FakePublish(relaysOk = false)
        val c = ComposeController(null, FakeThreads(emptyMap()), publish, eager())
        c.send("hello")
        advanceUntilIdle()
        assertTrue(c.state.value.pendingResend)
        assertFalse(c.state.value.done)
        c.send("hello")
        advanceUntilIdle()
        assertEquals(listOf("post:hello", "resend:0001"), publish.calls)
        assertTrue(c.state.value.pendingResend)
        publish.relaysOk = true
        c.send("hello")
        advanceUntilIdle()
        assertEquals(listOf("post:hello", "resend:0001", "resend:0001"), publish.calls)
        assertTrue(c.state.value.done)
        assertFalse(c.state.value.pendingResend)
    }

    @Test
    fun `thread resolves mention names of its notes`() = runTest {
        val carolKeys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")
        val carol = carolKeys.publicKey().toHex()
        val root = note('1', alice, 100, content = "cc nostr:${carolKeys.publicKey().toBech32()}")
        val threads = FakeThreads(mapOf(root.id to ThreadView(root, root, emptyList())))
        val known = FakeProfiles(mapOf(carol to Profile(carol, "carol", "Carol", null, null, null)))
        val c = ThreadController(root.id, threads, known, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        assertEquals(mapOf(carol to "Carol"), c.names.value)
    }

    @Test
    fun `deleting the focused note closes the thread, deleting a reply only removes it`() = runTest {
        val root = note('1', createdAt = 1000)
        val reply = note('2', createdAt = 2000, replyTo = root.id, root = root.id)
        val deletions = NoteDeletions()
        fun controller(focus: Note) = ThreadController(
            focus.id, FakeThreads(mapOf(focus.id to ThreadView(root, focus, listOf(reply)))), profiles, FakePublish(),
            decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), deletions = deletions,
        ).also { it.start() }

        val onRoot = controller(root)
        advanceUntilIdle()
        deletions.begin(reply.id); deletions.end(reply.id, deleted = true)
        advanceUntilIdle()
        assertEquals(listOf(root.id), onRoot.state.value.items.map { it.note.id })
        assertFalse(onRoot.state.value.closed)

        val onReply = controller(reply)
        advanceUntilIdle()
        assertTrue(onReply.state.value.closed)
    }

    private fun TestScope.threadController(focused: Note, replies: List<Note>, muted: StateFlow<Set<String>>, rootNote: Note? = null, mute: MuteRepository? = null): ThreadController {
        val root = rootNote ?: focused
        val deletions = NoteDeletions()
        return ThreadController(
            focused.id, FakeThreads(mapOf(focused.id to ThreadView(root, focused, replies))), profiles, FakePublish(),
            decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(),
            deletions = deletions, visibility = NoteVisibility(deletions, muted), mute = mute,
        )
    }

    @Test
    fun `a thread that turns out withdrawn stays empty when the muted set changes`() = runTest {
        val root = note('1', bob, createdAt = 1000)
        val reply = note('2', alice, createdAt = 2000, replyTo = root.id, root = root.id)
        val live = ThreadView(root, root, listOf(reply))
        val withdrawn = ThreadView(root, root, emptyList(), gone = true)
        val threads = object : ThreadRepository {
            override fun observe(noteId: String): Flow<ThreadView> = flow { emit(live); emit(withdrawn) }
            override suspend fun note(id: String): Note? = null
        }
        val muted = MutableStateFlow(emptySet<String>())
        val deletions = NoteDeletions()
        val c = ThreadController(
            root.id, threads, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(),
            deletions = deletions, visibility = NoteVisibility(deletions, muted),
        )
        c.start(); advanceUntilIdle()
        assertTrue(c.state.value.notFound)
        assertTrue(c.state.value.items.isEmpty())
        muted.value = setOf(alice); advanceUntilIdle()
        muted.value = emptySet(); advanceUntilIdle()
        assertTrue(c.state.value.items.isEmpty())
        assertTrue(c.state.value.notFound)
    }

    @Test
    fun `undo brings a muted reply back without a new emission of the thread`() = runTest {
        val root = note('1', bob, createdAt = 1000)
        val reply = note('2', alice, createdAt = 2000, replyTo = root.id, root = root.id)
        val mute = FakeMute(initial = setOf(alice))
        val c = threadController(root, replies = listOf(reply), muted = mute.muted, mute = mute)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(root.id), c.state.value.items.map { it.note.id })
        c.unmute(alice); advanceUntilIdle() // FakeThreads emits once only
        assertEquals(listOf(root.id, reply.id), c.state.value.items.map { it.note.id })
    }

    @Test
    fun `a muted reply disappears, replies to it and the focused note stay`() = runTest {
        val root = note('1', bob, createdAt = 1000)
        val muted = note('2', alice, createdAt = 2000, replyTo = root.id, root = root.id)
        val answer = note('3', bob, createdAt = 3000, replyTo = muted.id, root = root.id)
        val mutedFlow = MutableStateFlow(setOf(alice))
        val c = threadController(root, replies = listOf(muted, answer), muted = mutedFlow)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(root.id, answer.id), c.state.value.items.map { it.note.id })
        val onMuted = threadController(muted, replies = listOf(muted, answer), rootNote = root, muted = mutedFlow)
        onMuted.start(); advanceUntilIdle()
        assertTrue(onMuted.state.value.items.any { it.note.id == muted.id }) // the focused note is always shown
    }

    @Test
    fun `muting while the thread is open removes the account's replies but never the focused note`() = runTest {
        val root = note('1', alice, createdAt = 1000)
        val reply = note('2', alice, createdAt = 2000, replyTo = root.id, root = root.id)
        val other = note('3', bob, createdAt = 3000, replyTo = root.id, root = root.id)
        val mutedFlow = MutableStateFlow<Set<String>>(emptySet())
        val c = threadController(root, replies = listOf(reply, other), muted = mutedFlow)
        c.start(); advanceUntilIdle()
        assertEquals(3, c.state.value.items.size)
        mutedFlow.value = setOf(alice); advanceUntilIdle()
        assertEquals(listOf(root.id, other.id), c.state.value.items.map { it.note.id })
        assertFalse(c.state.value.closed)
    }
}

class FailingThreads : ThreadRepository {
    override fun observe(noteId: String): Flow<ThreadView> = throw IllegalArgumentException("bad id")
    override suspend fun note(id: String): Note? = throw IllegalArgumentException("bad id")
}
