package com.monostr.app.ui.discover

import com.monostr.app.ui.FakeDiscover
import com.monostr.app.ui.FakeFollows
import com.monostr.app.ui.FakeMute
import com.monostr.app.ui.FakeProfiles
import com.monostr.app.ui.followSet
import com.monostr.nostr.repo.FollowState
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

/** Spec 2026-10-27 §5: the discover sections and their follow actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverControllerTest {
    private val self = "e".repeat(64)
    private val author = "1".repeat(64)
    private fun pk(i: Int) = "%064x".format(i + 100)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    private fun list(d: String, vararg members: String) = followSet(d, members.toList(), author = author, title = "T $d")

    private fun TestScope.controller(discover: FakeDiscover, follows: FakeFollows = FakeFollows(), mute: FakeMute = FakeMute()) =
        DiscoverController(discover, follows, FakeProfiles(), mute, self, eager())

    @Test
    fun `rows are the first three not followed, not muted, not self`() = runTest {
        val follows = FakeFollows(mapOf(pk(1) to FollowState.Following))
        val c = controller(FakeDiscover(listOf(list("monero", self, pk(1), pk(2), pk(3), pk(4), pk(5)))), follows, FakeMute(setOf(pk(3))))
        c.start(); runCurrent()
        val s = c.state.value
        assertEquals(DiscoverStatus.Ready, s.status)
        assertEquals(listOf(pk(2), pk(4), pk(5)), s.sections.single().rows.map { it.profile.pubkey })
        assertTrue(s.sections.single().rows.none { it.following })
        assertFalse(s.sections.single().allFollowed)
    }

    @Test
    fun `a fully followed list shows its first three as following and allFollowed`() = runTest {
        val follows = FakeFollows((1..4).associate { pk(it) to FollowState.Following })
        val c = controller(FakeDiscover(listOf(list("art", pk(1), pk(2), pk(3), pk(4)))), follows)
        c.start(); runCurrent()
        val section = c.state.value.sections.single()
        assertEquals(listOf(pk(1), pk(2), pk(3)), section.rows.map { it.profile.pubkey })
        assertTrue(section.rows.all { it.following })
        assertTrue(section.allFollowed)
    }

    @Test
    fun `followAll follows every new non-muted member once`() = runTest {
        val follows = FakeFollows(mapOf(pk(1) to FollowState.Following))
        val monero = list("monero", self, pk(1), pk(2), pk(3))
        val c = controller(FakeDiscover(listOf(monero)), follows, FakeMute(setOf(pk(3))))
        c.start(); runCurrent()
        c.followAll(monero.address); runCurrent()
        assertEquals(listOf("followAll:1"), follows.calls)
        assertEquals(FollowState.Following, follows.states.value[pk(2)])
        assertTrue(follows.states.value[pk(3)] != FollowState.Following)
        assertTrue(c.state.value.sections.single().allFollowed)
    }

    @Test
    fun `a row just followed stays shown as following`() = runTest {
        val follows = FakeFollows()
        val c = controller(FakeDiscover(listOf(list("monero", pk(1), pk(2), pk(3), pk(4)))), follows)
        c.start(); runCurrent()
        c.follow(pk(2)); runCurrent()
        val rows = c.state.value.sections.single().rows
        assertEquals(listOf(pk(1), pk(2), pk(3)), rows.map { it.profile.pubkey })
        assertEquals(listOf(false, true, false), rows.map { it.following })
    }

    @Test
    fun `followAll twice writes once`() = runTest {
        val follows = FakeFollows()
        follows.gate = CompletableDeferred()
        val tech = list("tech", pk(1), pk(2))
        val c = controller(FakeDiscover(listOf(tech)), follows)
        c.start(); runCurrent()
        c.followAll(tech.address); c.followAll(tech.address); runCurrent()
        follows.gate!!.complete(Unit); runCurrent()
        assertEquals(1, follows.calls.count { it.startsWith("followAll") })
    }

    @Test
    fun `failed load offers retry`() = runTest {
        val discover = FakeDiscover()
        val c = controller(discover)
        c.start(); runCurrent()
        assertEquals(DiscoverStatus.Failed, c.state.value.status)
        discover.remote = listOf(list("popular", pk(1)))
        c.retry(); runCurrent()
        assertEquals(DiscoverStatus.Ready, c.state.value.status)
        assertEquals(listOf("popular"), c.state.value.sections.map { it.address.d })
    }

    @Test
    fun `local lists show before refresh finishes`() = runTest {
        val discover = FakeDiscover(listOf(list("monero", pk(1))), remote = listOf(list("monero", pk(1)), list("tech", pk(2))))
        discover.gate = CompletableDeferred()
        val c = controller(discover)
        c.start(); runCurrent()
        assertEquals(listOf("monero"), c.state.value.sections.map { it.address.d })
        discover.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("monero", "tech"), c.state.value.sections.map { it.address.d })
    }

    @Test
    fun `a follow error reaches the message and changes nothing`() = runTest {
        val follows = FakeFollows()
        follows.error = com.monostr.nostr.repo.FollowError.NoList()
        val c = controller(FakeDiscover(listOf(list("monero", pk(1)))), follows)
        c.start(); runCurrent()
        c.follow(pk(1)); runCurrent()
        assertTrue(c.state.value.message != null)
        assertFalse(c.state.value.sections.single().rows.single().following)
    }

    @Test
    fun `a follow keeps going when the screen that started it closes`() = runTest {
        // final review I4: leaving Search cancels its viewModelScope; the write runs on the write scope
        val follows = FakeFollows()
        follows.gate = CompletableDeferred()
        val ui = CoroutineScope(eager().coroutineContext + kotlinx.coroutines.Job())
        val tech = list("tech", pk(1), pk(2))
        val c = DiscoverController(FakeDiscover(listOf(tech)), follows, FakeProfiles(), FakeMute(), self, ui, writeScope = eager())
        c.start(); runCurrent()
        c.followAll(tech.address); runCurrent()
        ui.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        follows.gate!!.complete(Unit); runCurrent()
        assertEquals(FollowState.Following, follows.states.value[pk(2)])
    }
}
