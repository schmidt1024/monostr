package com.monostr.app.ui.dm

import com.monostr.app.R
import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.DmStore
import com.monostr.app.data.dm.InMemoryDmStore
import com.monostr.app.ui.FakeProfiles
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private val me = "e".repeat(64)
    private val bob = "b".repeat(64)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun msg(id: String, at: Long, outgoing: Boolean = false, text: String = "hi $id", status: DmStatus = if (outgoing) DmStatus.SENT else DmStatus.RECEIVED) =
        DmMessage(id, bob, outgoing, text, at, at, status, "w$id")

    private fun TestScope.controller(
        store: InMemoryDmStore,
        profiles: FakeProfiles = FakeProfiles(),
        send: suspend (String, String) -> DmStatus = { _, _ -> DmStatus.SENT },
        retry: suspend (String) -> DmStatus = { DmStatus.SENT },
        now: () -> Long = { 1_000 },
    ) = ChatController(bob, me, store, profiles, send, retry, eager(), now)

    @Test
    fun `messages are listed chronologically with media parsed`() = runTest {
        val store = InMemoryDmStore()
        store.upsert(msg("2", 200, outgoing = true, text = "look https://x.example/cat.jpg"))
        store.upsert(msg("1", 100, text = "hello"))
        val bobProfile = Profile(bob, "bob", "Bob", null, null, null)
        val c = controller(store, profiles = FakeProfiles(mapOf(bob to bobProfile)))
        c.start()
        advanceUntilIdle()
        val s = c.state.value
        assertFalse(s.loading)
        assertEquals(bobProfile, s.peer)
        assertEquals(listOf("1", "2"), s.messages.map { it.message.rumorId })
        assertEquals(bob, s.messages[0].note.author)
        assertEquals(me, s.messages[1].note.author) // outgoing bubbles show the own key
        val withImage = s.messages[1].note
        assertEquals(1, withImage.media.size)
        assertEquals("https://x.example/cat.jpg", withImage.media.single().url)
        assertEquals("look", withImage.displayContent)
        assertEquals("hello", s.messages[0].note.displayContent)
    }

    @Test
    fun `send adds a SENDING row through the store and reports FAILED as a message`() = runTest {
        val store = InMemoryDmStore()
        val gate = CompletableDeferred<Unit>()
        val sent = ArrayList<Pair<String, String>>()
        val c = controller(store, send = { peer, text ->
            sent += peer to text
            store.upsert(DmMessage("pending-1", peer, true, text, 500, 500, DmStatus.SENDING, null))
            gate.await()
            store.setStatus("pending-1", DmStatus.FAILED)
            DmStatus.FAILED
        })
        c.start()
        advanceUntilIdle()
        c.send("   ") // blank input is ignored
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
        c.send("hey")
        advanceUntilIdle()
        assertEquals(listOf(bob to "hey"), sent)
        assertTrue(c.state.value.sending)
        assertEquals(DmStatus.SENDING, c.state.value.messages.single().message.status)
        assertNull(c.state.value.message)
        gate.complete(Unit)
        advanceUntilIdle()
        val s = c.state.value
        assertFalse(s.sending)
        assertEquals(DmStatus.FAILED, s.messages.single().message.status)
        assertEquals(uiText(R.string.chat_error_send), s.message)
        c.clearMessage()
        assertNull(c.state.value.message)
    }

    @Test
    fun `retry reports FAILED as a message`() = runTest {
        val store = InMemoryDmStore()
        store.upsert(msg("1", 100, outgoing = true, status = DmStatus.FAILED))
        val retried = ArrayList<String>()
        val c = controller(store, retry = { retried += it; DmStatus.FAILED })
        c.start()
        advanceUntilIdle()
        c.retry("1")
        advanceUntilIdle()
        assertEquals(listOf("1"), retried)
        assertEquals(uiText(R.string.chat_error_send), c.state.value.message)
    }

    @Test
    fun `markRead moves the read marker`() = runTest {
        val store = InMemoryDmStore()
        store.upsert(msg("1", 100))
        store.upsert(msg("2", 200))
        assertEquals(2, store.conversations().first()[0].unread)
        var clock = 300L
        val c = controller(store, now = { clock })
        c.start()
        c.setVisible(true)
        advanceUntilIdle()
        assertEquals(0, store.conversations().first()[0].unread)
        // a message arriving while the chat is open is read at once
        clock = 400
        store.upsert(msg("3", 350))
        advanceUntilIdle()
        assertEquals(0, store.conversations().first()[0].unread)
        assertEquals(400L, store.conversations().first()[0].readAt)
        // a peer whose clock runs an hour ahead: the marker still covers the message
        store.upsert(msg("4", clock + 3_600))
        advanceUntilIdle()
        assertEquals(0, store.conversations().first()[0].unread)
        // ...but a timestamp days ahead moves the marker one day at most
        store.upsert(msg("5", clock + 3 * 86_400))
        advanceUntilIdle()
        assertEquals(clock + 86_400, store.conversations().first()[0].readAt)
        assertEquals(1, store.conversations().first()[0].unread)
    }

    @Test
    fun `messages stay unread while the chat is not visible`() = runTest {
        val store = InMemoryDmStore()
        store.upsert(msg("1", 100))
        val c = controller(store, now = { 300 })
        c.start()
        advanceUntilIdle()
        assertEquals(1, store.conversations().first()[0].unread) // not yet visible
        c.setVisible(true)
        advanceUntilIdle()
        assertEquals(0, store.conversations().first()[0].unread)
        c.setVisible(false) // app in background
        store.upsert(msg("2", 350)) // newer than the marker set at now = 300
        advanceUntilIdle()
        assertEquals(1, store.conversations().first()[0].unread)
        c.setVisible(true)
        advanceUntilIdle()
        assertEquals(0, store.conversations().first()[0].unread)
    }

    @Test
    fun `a failing store ends loading with an error`() = runTest {
        val broken = object : DmStore by InMemoryDmStore() {
            override fun messages(peer: String): Flow<List<DmMessage>> = flow { throw IllegalStateException("db gone") }
        }
        val c = ChatController(bob, me, broken, FakeProfiles(), { _, _ -> DmStatus.SENT }, { DmStatus.SENT }, eager(), { 1_000 })
        c.start()
        advanceUntilIdle()
        assertFalse(c.state.value.loading)
        assertEquals(uiText(R.string.messages_error_load), c.state.value.message)
    }

    @Test
    fun `mentions in messages get names`() = runTest {
        val carolKeys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")
        val carol = carolKeys.publicKey().toHex()
        val store = InMemoryDmStore()
        store.upsert(msg("1", 100, text = "ask nostr:${carolKeys.publicKey().toBech32()}"))
        val c = controller(store, profiles = FakeProfiles(mapOf(carol to Profile(carol, "carol", "Carol", null, null, null))))
        c.start()
        advanceUntilIdle()
        assertEquals(mapOf(carol to "Carol"), c.names.value)
    }

    @Test
    fun `a message that links a note carries it as a quote`() = runTest {
        val quoted = "ab".repeat(32)
        val store = InMemoryDmStore()
        store.upsert(msg("1", 100, text = "look\n" + rust.nostr.sdk.EventId.parse(quoted).toNostrUri()))
        val c = controller(store)
        c.start()
        advanceUntilIdle()
        assertEquals(quoted, c.state.value.messages[0].note.quotedId)
        assertEquals("look", c.state.value.messages[0].note.displayContent)
    }
}
