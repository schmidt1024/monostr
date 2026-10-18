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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-18 §2.4: create and edit a list. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListEditControllerTest {
    private val self = "e".repeat(64)
    private val bob = "b".repeat(64)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `a new list is private by default and starts with the person it was made for`() = runTest {
        val lists = FakeLists()
        val c = ListEditController(null, self, bob, lists, FakeFeedTabs(), eager())
        c.start()
        assertTrue(c.state.value.isNew); assertTrue(c.state.value.private); assertFalse(c.state.value.canSave)
        c.setTitle("  Friends  ")
        c.setDescription("close ones")
        c.save(); runCurrent()
        val saved = lists.ownState.value.single()
        assertEquals(ListAddress(30000, self, "newid0000001"), saved.address)
        assertEquals("Friends", saved.title)
        assertEquals("close ones", saved.description)
        assertEquals(listOf(bob), saved.members)
        assertTrue(saved.private)
        assertTrue(c.state.value.done); assertFalse(c.state.value.deleted)
    }

    @Test
    fun `title and description are cut at 50 and 200, a blank title cannot be saved`() = runTest {
        val c = ListEditController(null, self, null, FakeLists(), FakeFeedTabs(), eager())
        c.setTitle("x".repeat(80)); assertEquals(50, c.state.value.title.length)
        c.setDescription("y".repeat(300)); assertEquals(200, c.state.value.description.length)
        c.setTitle("   "); assertFalse(c.state.value.canSave)
    }

    @Test
    fun `making an existing private list public asks first, a new list switches at once`() = runTest {
        val friends = followSet("friends", listOf(bob), author = self, private = true)
        val lists = FakeLists(listOf(friends))
        val c = ListEditController(friends.address, self, null, lists, FakeFeedTabs(), eager())
        c.start(); runCurrent()
        assertEquals(friends.title, c.state.value.title)
        c.setPrivate(false)
        assertTrue(c.state.value.confirmPublic); assertTrue(c.state.value.private)
        c.dismissPublic()
        assertTrue(c.state.value.private)
        c.setPrivate(false); c.confirmPublic()
        assertFalse(c.state.value.private)
        c.save(); runCurrent()
        assertEquals(listOf("edit:friends:false"), lists.calls)
        val fresh = ListEditController(null, self, null, FakeLists(), FakeFeedTabs(), eager())
        fresh.setPrivate(false)
        assertFalse(fresh.state.value.confirmPublic); assertFalse(fresh.state.value.private)
    }

    @Test
    fun `saving keeps members added while the edit screen was open`() = runTest {
        val friends = followSet("friends", listOf(bob), author = self)
        val lists = FakeLists(listOf(friends))
        val c = ListEditController(friends.address, self, null, lists, FakeFeedTabs(), eager())
        c.start(); runCurrent()
        val carol = "c".repeat(64)
        lists.setMember(friends.address, carol, true) // from the add-to-list sheet meanwhile
        c.setTitle("Renamed")
        c.save(); runCurrent()
        val saved = lists.ownState.value.single()
        assertEquals("Renamed", saved.title)
        assertEquals(listOf(bob, carol), saved.members)
        assertTrue(c.state.value.done)
    }

    @Test
    fun `a failed save stays open and offers Retry`() = runTest {
        val lists = FakeLists().apply { result = SaveResult.Failed }
        val c = ListEditController(null, self, null, lists, FakeFeedTabs(), eager())
        c.setTitle("A")
        c.save(); runCurrent()
        assertFalse(c.state.value.done); assertFalse(c.state.value.saving)
        val m = c.state.value.message!!
        assertEquals(uiText(R.string.list_save_failed), m.text)
        lists.result = SaveResult.Ok
        m.retry!!()
        runCurrent()
        assertTrue(c.state.value.done)
    }

    @Test
    fun `delete asks, deletes, unpins and closes the list too`() = runTest {
        val friends = followSet("friends", listOf(bob), author = self)
        val lists = FakeLists(listOf(friends))
        val pins = FakeFeedTabs(listOf(friends.address))
        val c = ListEditController(friends.address, self, null, lists, pins, eager())
        c.start(); runCurrent()
        c.askDelete()
        assertTrue(c.state.value.confirmDelete)
        c.delete(); runCurrent()
        assertEquals(listOf("delete:friends"), lists.calls)
        assertEquals(emptyList<ListAddress>(), pins.state.value)
        assertTrue(c.state.value.done); assertTrue(c.state.value.deleted)
    }
}
