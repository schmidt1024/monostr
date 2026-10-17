package com.monostr.app.session

import com.monostr.app.data.PendingSetupStore
import com.monostr.app.ui.FakeDmSettings
import com.monostr.app.ui.FakeFollows
import com.monostr.app.ui.FakePublish
import com.monostr.nostr.repo.FollowError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-17 §4/§7/§11: the open parts of a new account go out in order; done parts are saved at once. */
class PendingSetupPublisherTest {
    private val pk = "a".repeat(64)
    private val relays = listOf("wss://relay.monostr.com", "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
    private val dmRelays = listOf("wss://relay.monostr.com")
    private val follows = listOf("1".repeat(64), "2".repeat(64), "3".repeat(64))
    private fun setup(follows: List<String> = this.follows) =
        PendingSetup(name = "Alina", relays = relays, dmRelays = dmRelays, follows = follows, followsDone = follows.isEmpty())

    private class MemoryStore : PendingSetupStore {
        val records = HashMap<String, PendingSetup>()
        override suspend fun load(pubkey: String) = records[pubkey]
        override suspend fun save(pubkey: String, setup: PendingSetup) { records[pubkey] = setup }
        override suspend fun remove(pubkey: String) { records.remove(pubkey) }
    }

    private val store = MemoryStore()
    private val publish = FakePublish()
    private val followRepo = FakeFollows()
    private val dmSettings = FakeDmSettings(relays = listOf("wss://other.example"))
    private val existing = HashSet<Int>()
    private fun publisher() = PendingSetupPublisher(store, pk, publish, followRepo, dmSettings, exists = { it in existing })

    @Test
    fun `publishes the four parts in order and removes the record`() = runTest {
        store.save(pk, setup())
        assertTrue(publisher().run())
        assertEquals(listOf("profile", "initialrelays:4", "dmrelays:1", "initial:3"), publish.calls + followRepo.calls)
        assertNull(store.load(pk))
        assertTrue(dmSettings.listAdoptedState.value)
        assertEquals(dmRelays, dmSettings.relayState.value)
        assertEquals(listOf("""{"name":"Alina"}"""), publish.profileJson)
    }

    @Test
    fun `a failing part stays pending, the others are marked done`() = runTest {
        store.save(pk, setup())
        publish.refusedKinds += 10050
        assertFalse(publisher().run())
        val left = store.load(pk)!!
        assertTrue(left.profileDone)
        assertTrue(left.relaysDone)
        assertFalse(left.dmRelaysDone)
        assertTrue(left.followsDone)
        assertFalse(dmSettings.listAdoptedState.value)
        assertEquals(listOf("wss://other.example"), dmSettings.relayState.value)
    }

    @Test
    fun `restart resumes only the open parts`() = runTest {
        store.save(pk, setup().copy(profileDone = true, relaysDone = true))
        assertTrue(publisher().run())
        assertEquals(listOf("dmrelays:1"), publish.calls)
        assertEquals(listOf("initial:3"), followRepo.calls)
        assertNull(store.load(pk))
    }

    @Test
    fun `an existing event of the kind drops the part`() = runTest {
        store.save(pk, setup())
        existing += 0
        publish.refusedKinds += 10002 // keeps the record so that the saved flags can be read
        assertFalse(publisher().run())
        assertTrue("profile" !in publish.calls)
        assertTrue(store.load(pk)!!.profileDone)
        assertFalse(store.load(pk)!!.relaysDone)
    }

    @Test
    fun `no follows part publishes no kind 3`() = runTest {
        store.save(pk, setup(follows = emptyList()))
        assertTrue(publisher().run())
        assertTrue(followRepo.calls.none { it.startsWith("initial:") })
        assertNull(store.load(pk))
    }

    @Test
    fun `an empty follow list counts as done even when the flag is not set`() = runTest {
        store.save(pk, setup(follows = emptyList()).copy(followsDone = false))
        assertTrue(publisher().run())
        assertTrue(followRepo.calls.none { it.startsWith("initial:") })
        assertNull(store.load(pk))
    }

    @Test
    fun `a thrown error counts as not done and is not swallowed when cancelled`() = runTest {
        store.save(pk, setup())
        followRepo.error = FollowError.NotAccepted()
        assertFalse(publisher().run())
        val left = store.load(pk)!!
        assertFalse(left.followsDone)
        assertTrue(left.profileDone && left.relaysDone && left.dmRelaysDone)

        followRepo.error = CancellationException("left")
        var thrown: Throwable? = null
        try { publisher().run() } catch (e: CancellationException) { thrown = e }
        assertTrue(thrown is CancellationException)
        assertFalse(store.load(pk)!!.followsDone)
    }

    @Test
    fun `no record means nothing to do`() = runTest {
        assertTrue(publisher().run())
        assertTrue(publish.calls.isEmpty())
    }
}
