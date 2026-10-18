package com.monostr.app.ui.lists

import com.monostr.app.R
import com.monostr.app.ui.FakeLists
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.followSet
import com.monostr.nostr.model.SaveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-18 §2.5: a checkbox per own list, each tick saves at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddToListControllerTest {
    private val self = "e".repeat(64)
    private val bob = "b".repeat(64)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    private fun AddToListController.checked() = state.value.rows!!.associate { it.set.address.d to it.checked }

    @Test
    fun `rows show membership, a tick shows at once and saves`() = runTest {
        val lists = FakeLists(listOf(followSet("a", listOf(bob), author = self), followSet("b", emptyList(), author = self)))
        val c = AddToListController(bob, lists, eager())
        c.start(); runCurrent()
        assertEquals(mapOf("a" to true, "b" to false), c.checked())
        val gate = CompletableDeferred<Unit>()
        lists.gate = gate
        c.toggle(c.state.value.rows!![1]); runCurrent()
        assertEquals(mapOf("a" to true, "b" to true), c.checked()) // before the relays answered
        gate.complete(Unit); runCurrent()
        assertEquals(mapOf("a" to true, "b" to true), c.checked())
        assertEquals(listOf("member:b:bbbb:true"), lists.calls)
    }

    @Test
    fun `a failed tick goes back and offers Retry`() = runTest {
        val lists = FakeLists(listOf(followSet("a", emptyList(), author = self))).apply { result = SaveResult.Failed }
        val c = AddToListController(bob, lists, eager())
        c.start(); runCurrent()
        c.toggle(c.state.value.rows!!.single()); runCurrent()
        assertEquals(mapOf("a" to false), c.checked())
        val m = c.state.value.message!!
        assertEquals(uiText(R.string.list_save_failed), m.text)
        lists.result = SaveResult.Ok
        m.retry!!()
        runCurrent()
        assertEquals(mapOf("a" to true), c.checked())
    }

    @Test
    fun `a double tick ends unticked, and a locked list cannot be ticked`() = runTest {
        val lists = FakeLists(listOf(followSet("a", emptyList(), author = self), followSet("l", emptyList(), author = self, private = true, locked = true)))
        val c = AddToListController(bob, lists, eager())
        c.start(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        lists.gate = gate
        c.toggle(c.state.value.rows!!.first { it.set.address.d == "a" })
        runCurrent()
        c.toggle(c.state.value.rows!!.first { it.set.address.d == "a" })
        runCurrent()
        assertEquals(false, c.checked()["a"]) // the latest intent shows while both writes wait
        gate.complete(Unit); runCurrent()
        assertEquals(false, c.checked()["a"])
        assertEquals(listOf("member:a:bbbb:true", "member:a:bbbb:false"), lists.calls)
        c.toggle(c.state.value.rows!!.first { it.set.address.d == "l" }); runCurrent()
        assertEquals(2, lists.calls.size)
    }

    @Test
    fun `a tick survives the screen's scope`() = runTest {
        val lists = FakeLists(listOf(followSet("a", emptyList(), author = self)))
        val screen = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val c = AddToListController(bob, lists, screen, writeScope = eager())
        c.start(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        lists.gate = gate
        c.toggle(c.state.value.rows!!.single()); runCurrent()
        screen.cancel() // the sheet and the profile are left before the relays answered
        gate.complete(Unit); runCurrent()
        assertTrue(bob in lists.ownState.value.single().members)
        assertFalse(lists.calls.isEmpty())
    }
}
