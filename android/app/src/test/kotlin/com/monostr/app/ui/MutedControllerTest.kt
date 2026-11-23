package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.settings.MutedController
import com.monostr.app.ui.settings.MutedRoot
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
            override fun prefetch(pubkeys: Collection<String>): Unit = throw IllegalArgumentException("not a pubkey")
        }
        val c = MutedController(FakeMute(initial = setOf(bob, carol)), profiles, eager())
        c.start(); runCurrent()
        assertEquals(listOf(bob, carol), c.state.value.entries.map { it.first })
        assertEquals(Profile.empty(bob), c.state.value.entries.first().second)
    }

    @Test
    fun `a muted account's profile that lands later fills its row`() = runTest {
        val profiles = FakeProfiles()
        val c = MutedController(FakeMute(initial = setOf(bob, carol)), profiles, eager())
        c.start(); runCurrent()
        assertEquals(Profile.empty(carol), c.state.value.entries.first { it.first == carol }.second)
        profiles.land(Profile(carol, "carol", "Carol", null, null, null)); runCurrent()
        assertEquals("Carol", c.state.value.entries.first { it.first == carol }.second.shownName)
    }

    @Test
    fun `muted conversations are listed and unmuting removes the row`() = runTest {
        val root = "d".repeat(64)
        val mute = FakeMute()
        mute.mutedThreads.value = setOf(root)
        val c = MutedController(mute, FakeProfiles(), eager())
        c.start(); runCurrent()
        assertEquals(listOf(root), c.state.value.threads.map { it.rootId })
        assertTrue(c.state.value.loaded)
        c.unmuteThread(root); runCurrent()
        assertTrue(c.state.value.threads.isEmpty())
        assertEquals(null, c.state.value.message)
    }

    @Test
    fun `a stored root shows its first line, a missing one stays without a snippet`() = runTest {
        val stored = "d".repeat(64)
        val missing = "e".repeat(64)
        val mute = FakeMute()
        mute.mutedThreads.value = setOf(stored, missing)
        val c = MutedController(mute, FakeProfiles(), eager(), roots = { ids -> ids.filter { it == stored }.associateWith { MutedRoot("First line", bob) } })
        c.start(); runCurrent()
        assertEquals("First line", c.state.value.threads.first { it.rootId == stored }.snippet)
        assertEquals(null, c.state.value.threads.first { it.rootId == missing }.snippet)
    }

    @Test
    fun `a stored root with a stored author shows the author's picture, otherwise none`() = runTest {
        val stored = "d".repeat(64)
        val missing = "e".repeat(64)
        val noProfile = "f".repeat(64)
        val mute = FakeMute()
        mute.mutedThreads.value = setOf(stored, missing, noProfile)
        val profiles = FakeProfiles(mapOf(bob to bobProfile.copy(picture = "https://x/bob.png")))
        val c = MutedController(mute, profiles, eager(), roots = { ids ->
            mapOf(stored to MutedRoot("Hi", bob), noProfile to MutedRoot(null, carol)).filterKeys { it in ids }
        })
        c.start(); runCurrent()
        assertEquals("https://x/bob.png", c.state.value.threads.first { it.rootId == stored }.picture)
        assertEquals(null, c.state.value.threads.first { it.rootId == missing }.picture)
        assertEquals(null, c.state.value.threads.first { it.rootId == noProfile }.picture)
        // the author's profile landing later fills the picture in
        profiles.land(Profile.empty(carol).copy(picture = "https://x/carol.png")); runCurrent()
        assertEquals("https://x/carol.png", c.state.value.threads.first { it.rootId == noProfile }.picture)
    }

    @Test
    fun `the newest mute is listed first, accounts and conversations`() = runTest {
        val mute = FakeMute()
        mute.mute(bob); mute.mute(carol)
        mute.muteThread("d".repeat(64)); mute.muteThread("a".repeat(64))
        val c = MutedController(mute, FakeProfiles(), eager())
        c.start(); runCurrent()
        assertEquals(listOf(carol, bob), c.state.value.entries.map { it.first })
        assertEquals(listOf("a".repeat(64), "d".repeat(64)), c.state.value.threads.map { it.rootId })
    }

    @Test
    fun `firstLine takes the first non-blank line and cuts at 80`() {
        assertEquals("Hello", com.monostr.app.ui.settings.firstLine("\n  \nHello\nworld"))
        assertEquals(null, com.monostr.app.ui.settings.firstLine("  \n "))
        assertEquals(80, com.monostr.app.ui.settings.firstLine("x".repeat(200))!!.length)
    }
}
