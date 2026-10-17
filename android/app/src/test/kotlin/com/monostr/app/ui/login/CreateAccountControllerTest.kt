package com.monostr.app.ui.login

import com.monostr.app.R
import com.monostr.app.session.NewKey
import com.monostr.app.session.NewKeys
import com.monostr.app.session.PendingSetup
import com.monostr.app.ui.common.uiText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreateAccountControllerTest {
    private val jack = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2"

    private class Rec(val fail: Boolean = false) : CreateAccountActions {
        var key: NewKey? = null
        var setup: PendingSetup? = null
        override suspend fun create(key: NewKey, setup: PendingSetup) {
            if (fail) throw IllegalStateException("boom")
            this.key = key
            this.setup = setup
        }
    }

    private fun controller(rec: Rec = Rec(), keyGen: () -> NewKey = NewKeys::generate) = CreateAccountController(
        rec,
        relays = { listOf("wss://a") },
        dmRelays = listOf("wss://dm"),
        keyGen = keyGen,
    )

    private fun CreateAccountController.toFollow() {
        next(); setSaved(true); next(); setName("Alina"); next()
    }

    @Test
    fun `the key is made once and survives going back`() = runTest {
        var made = 0
        val c = controller(keyGen = { made++; NewKeys.generate() })
        val nsec = c.state.value.nsec
        c.next(); c.back(); c.next()
        assertEquals(1, made)
        assertEquals(nsec, c.state.value.nsec)
    }

    @Test
    fun `backup needs the tick`() = runTest {
        val c = controller()
        c.next()
        assertEquals(CreateStep.Backup, c.state.value.step)
        assertFalse(c.state.value.canNext)
        c.next()
        assertEquals(CreateStep.Backup, c.state.value.step)
        c.setSaved(true)
        assertTrue(c.state.value.canNext)
        c.next()
        assertEquals(CreateStep.Name, c.state.value.step)
    }

    @Test
    fun `name must be non blank and at most 64 after trim`() = runTest {
        val rec = Rec()
        val c = controller(rec)
        c.next(); c.setSaved(true); c.next()
        c.setName("  ")
        assertFalse(c.state.value.nameValid)
        assertFalse(c.state.value.canNext)
        c.setName("a".repeat(65))
        assertFalse(c.state.value.nameValid)
        c.setName(" Alina ")
        assertTrue(c.state.value.nameValid)
        c.next()
        assertEquals(CreateStep.Follow, c.state.value.step)
        c.finish()
        assertEquals("Alina", rec.setup!!.name)
    }

    @Test
    fun `back on welcome asks first and then leaves`() = runTest {
        val c = controller()
        assertTrue(c.back())
        assertTrue(c.state.value.confirmCancel)
        assertFalse(c.back())
        c.dismissCancel()
        assertFalse(c.state.value.confirmCancel)
        c.confirmCancel()
        assertTrue(c.state.value.confirmCancel)
    }

    @Test
    fun `finish builds the pending setup with the ticked follows`() = runTest {
        val rec = Rec()
        val c = controller(rec)
        c.toFollow()
        c.toggle(jack)
        c.finish()
        assertEquals(c.copyText(), rec.key!!.nsec)
        val s = rec.setup!!
        assertEquals("Alina", s.name)
        assertEquals(listOf("wss://a"), s.relays)
        assertEquals(listOf("wss://dm"), s.dmRelays)
        assertEquals(SuggestedFollows.all.map { it.pubkey } - jack, s.follows)
        assertFalse(s.profileDone || s.relaysDone || s.dmRelaysDone || s.followsDone)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `nothing ticked gives no follows part`() = runTest {
        val rec = Rec()
        val c = controller(rec)
        c.toFollow()
        SuggestedFollows.all.forEach { c.toggle(it.pubkey) }
        c.finish()
        assertTrue(rec.setup!!.follows.isEmpty())
        assertTrue(rec.setup!!.followsDone)
    }

    @Test
    fun `a failing create shows an error and stays on Follow`() = runTest {
        val c = controller(Rec(fail = true))
        c.toFollow()
        c.finish()
        assertEquals(uiText(R.string.login_error_failed), c.state.value.error)
        assertFalse(c.state.value.busy)
        assertEquals(CreateStep.Follow, c.state.value.step)
    }

    @Test
    fun `the state text never contains the nsec`() = runTest {
        val c = controller()
        val nsec = c.state.value.nsec
        assertTrue(nsec.startsWith("nsec1"))
        assertFalse(c.state.value.toString().contains(nsec))
        assertFalse(c.state.value.toString().contains(nsec.removePrefix("nsec1")))
        assertTrue(c.state.value.toString().contains(c.state.value.npub))
    }

    @Test
    fun `finish does nothing before the follow step`() = runTest {
        val rec = Rec()
        val c = controller(rec)
        c.next(); c.setSaved(true); c.next(); c.setName("Alina")
        c.finish()
        assertEquals(null, rec.setup)
        assertEquals(CreateStep.Name, c.state.value.step)
        assertFalse(c.state.value.busy)
    }
}
