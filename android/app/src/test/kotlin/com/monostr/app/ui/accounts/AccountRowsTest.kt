package com.monostr.app.ui.accounts

import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SignerType
import com.monostr.app.session.switcherDisplay
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountRowsTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun `rows keep the registry order and mark the active one`() = runTest {
        val rows = accountRows(
            listOf(SessionInfo(a, SignerType.LOCAL, null, name = "Alice", picture = "https://p/a.png"), SessionInfo(b, SignerType.AMBER, "pkg", name = " ")),
            active = b,
        ) { false }
        assertEquals(listOf(a, b), rows.map { it.pubkey })
        assertEquals(listOf(false, true), rows.map { it.active })
        assertEquals("Alice", rows[0].name)
        assertEquals("https://p/a.png", rows[0].picture)
        // a blank name counts as none: the short npub is the title then
        assertNull(rows[1].name)
        assertTrue(rows[1].amber)
        assertTrue(rows.all { it.npubShort.isNotBlank() })
    }

    @Test
    fun `only an inactive account is asked whether it needs a sign-in`() = runTest {
        val asked = mutableListOf<String>()
        val rows = accountRows(listOf(SessionInfo(a, SignerType.LOCAL, null), SessionInfo(b, SignerType.LOCAL, null)), active = a) {
            asked += it.pubkey
            true
        }
        assertEquals(listOf(b), asked)
        assertFalse(rows[0].needsSignIn)
        assertTrue(rows[1].needsSignIn)
    }

    @Test
    fun `only the active account's avatar may use the network`() {
        assertEquals(coil3.request.CachePolicy.ENABLED, switcherAvatarNetwork(active = true))
        // an inactive account's picture: caches only, never fetched over the active account's connection
        assertEquals(coil3.request.CachePolicy.DISABLED, switcherAvatarNetwork(active = false))
        assertFalse(switcherAvatarNetwork(active = false).readEnabled)
    }

    @Test
    fun `switcher display prefers the display name and drops blanks`() {
        assertEquals("Ali" to "https://p", switcherDisplay(Profile(a, "alice", "Ali", "https://p", null, null)))
        assertEquals("alice" to null, switcherDisplay(Profile(a, "alice", " ", "", null, null)))
        // nothing known yet (the empty profile before the cache has it): no write over what the registry holds
        assertNull(switcherDisplay(Profile.empty(a)))
    }
}
