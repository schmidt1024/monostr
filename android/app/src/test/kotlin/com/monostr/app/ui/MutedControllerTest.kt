package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.settings.MutedController
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ListOutcome
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MutedControllerTest {
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private val bobProfile = Profile.empty(bob).copy(name = "Bob")

    @Test
    fun `entries come from the muted set with their profiles, the list is loaded interactively`() = runTest {
        val mute = FakeMute(initial = setOf(bob, carol))
        val profiles = FakeProfiles(mapOf(bob to bobProfile))
        val c = MutedController(mute, profiles, eager())
        c.start(); runCurrent()
        assertEquals(listOf(bob, carol), c.state.value.entries.map { it.first })
        assertEquals("Bob", c.state.value.entries.first().second.shownName)
        assertEquals(listOf(true), mute.loadCalls)
        assertEquals(listOf(setOf(bob, carol)), profiles.prefetched.map { it.toSet() })
        assertFalse(c.state.value.readOnly)
    }

    @Test
    fun `unmute removes the row`() = runTest {
        val mute = FakeMute(initial = setOf(bob, carol))
        val c = MutedController(mute, FakeProfiles(mapOf(bob to bobProfile)), eager())
        c.start(); runCurrent()
        c.unmute(bob); runCurrent()
        assertEquals(listOf(carol), c.state.value.entries.map { it.first })
        assertEquals(null, c.state.value.message)
    }

    @Test
    fun `a failed unmute brings the row back with the failure text`() = runTest {
        val mute = FakeMute(initial = setOf(bob), outcome = ListOutcome.PublishFailed)
        val c = MutedController(mute, FakeProfiles(mapOf(bob to bobProfile)), eager())
        c.start(); runCurrent()
        c.unmute(bob); runCurrent()
        assertEquals(listOf(bob), c.state.value.entries.map { it.first })
        assertEquals(uiText(R.string.error_send_failed), c.state.value.message)
    }

    @Test
    fun `a list that is loaded but not writable is read-only`() = runTest {
        val mute = FakeMute(initial = setOf(bob), writable = false)
        val c = MutedController(mute, FakeProfiles(), eager())
        c.start(); runCurrent()
        assertTrue(c.state.value.readOnly)
        assertEquals(1, c.state.value.entries.size)
    }

    @Test
    fun `an empty set shows no rows`() = runTest {
        val c = MutedController(FakeMute(), FakeProfiles(), eager())
        c.start(); runCurrent()
        assertTrue(c.state.value.entries.isEmpty())
    }

    @Test
    fun `rows show at once with the short pubkey while the profiles load, and a muted entry counts as loaded`() = runTest {
        val c = MutedController(FakeMute(initial = setOf(carol)), FakeProfiles(), eager())
        assertFalse(c.state.value.loaded)
        c.start(); runCurrent()
        assertTrue(c.state.value.loaded)
        assertEquals(Profile.empty(carol), c.state.value.entries.single().second)
    }

    @Test
    fun `nobody is muted shows only once the list is known loaded`() = runTest {
        val mute = FakeMute().apply { loadGate = CompletableDeferred() }
        val c = MutedController(mute, FakeProfiles(), eager())
        c.start(); runCurrent()
        assertFalse(c.state.value.loaded) // the interactive load is still running
        mute.loadGate!!.complete(Unit); runCurrent()
        assertTrue(c.state.value.loaded)
        assertTrue(c.state.value.entries.isEmpty())
    }

    @Test
    fun `a failing profile prefetch still lists the entries`() = runTest {
        val profiles = object : ProfileRepository by FakeProfiles() {
            override suspend fun prefetch(pubkeys: Collection<String>) = throw IllegalArgumentException("not a pubkey")
        }
        val c = MutedController(FakeMute(initial = setOf(bob, carol)), profiles, eager())
        c.start(); runCurrent()
        assertEquals(listOf(bob, carol), c.state.value.entries.map { it.first })
        assertEquals(Profile.empty(bob), c.state.value.entries.first().second)
    }
}
