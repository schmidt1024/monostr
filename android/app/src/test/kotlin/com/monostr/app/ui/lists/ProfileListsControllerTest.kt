package com.monostr.app.ui.lists

import com.monostr.app.R
import com.monostr.app.ui.FakeFeedTabs
import com.monostr.app.ui.FakeLists
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.followSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-18 §2.2: the "Lists" tab of a profile. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileListsControllerTest {
    private val self = "e".repeat(64)
    private val bob = "b".repeat(64)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `the own profile shows every own list, private ones too, and follows changes`() = runTest {
        val mine = listOf(followSet("a", listOf(bob), author = self, private = true), followSet("b", emptyList(), author = self))
        val lists = FakeLists(mine)
        val c = ProfileListsController(self, self, lists, FakeFeedTabs(), eager())
        assertTrue(c.isSelf)
        c.open(); runCurrent()
        assertEquals(mine, c.state.value.lists)
        lists.ownState.value = mine.take(1); runCurrent()
        assertEquals(1, c.state.value.lists!!.size)
    }

    @Test
    fun `another profile shows that account's lists, loaded when the tab opens`() = runTest {
        val theirs = followSet("x", listOf(self), author = bob)
        val c = ProfileListsController(bob, self, FakeLists().apply { byAuthor = mapOf(bob to listOf(theirs)) }, FakeFeedTabs(), eager())
        assertNull(c.state.value.lists)
        c.open(); runCurrent()
        assertEquals(listOf(theirs), c.state.value.lists)
    }

    @Test
    fun `pin toggles, a sixth pin says the limit, a failed pin offers Retry`() = runTest {
        val pins = FakeFeedTabs((1..5).map { ListAddress(30000, self, "p$it") })
        val target = followSet("x", listOf(self), author = bob)
        val c = ProfileListsController(bob, self, FakeLists().apply { byAuthor = mapOf(bob to listOf(target)) }, pins, eager())
        c.open(); runCurrent()
        c.togglePin(target); runCurrent()
        assertEquals(uiText(R.string.list_pin_limit), c.state.value.message?.text)
        assertEquals(5, pins.state.value.size)
        c.clearMessage()
        pins.state.value = pins.state.value.drop(1)
        pins.result = SaveResult.Failed
        c.togglePin(target); runCurrent()
        val m = c.state.value.message!!
        assertEquals(uiText(R.string.list_save_failed), m.text)
        assertFalse(target.address in c.state.value.pinned)
        pins.result = SaveResult.Ok
        m.retry!!()
        runCurrent()
        assertTrue(target.address in c.state.value.pinned)
        c.togglePin(target); runCurrent()
        assertFalse(target.address in c.state.value.pinned)
    }

    @Test
    fun `a pin still gets written after the screen scope is cancelled`() = runTest {
        val pins = FakeFeedTabs()
        val target = followSet("x", listOf(self), author = bob)
        val screen = Job()
        val scope = CoroutineScope(eager().coroutineContext + screen)
        val c = ProfileListsController(bob, self, FakeLists(), pins, scope, writeScope = eager())
        screen.cancel()
        c.togglePin(target); runCurrent()
        assertEquals(listOf(target.address), pins.state.value)
    }
}
