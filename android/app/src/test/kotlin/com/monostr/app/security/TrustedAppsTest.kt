package com.monostr.app.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrustedAppsTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `an app is trusted only with its own publisher's certificate`() {
        val translator = TrustedApps.CERTIFICATES.getValue(TrustedApps.OFFLINE_TRANSLATOR).single()
        val tor = TrustedApps.CERTIFICATES.getValue(TrustedApps.TOR_BROWSER).single()
        assertTrue(TrustedApps.isTrusted(TrustedApps.OFFLINE_TRANSLATOR) { hex(it) == translator })
        assertFalse(TrustedApps.isTrusted(TrustedApps.OFFLINE_TRANSLATOR) { hex(it) == tor }, "another trusted app's certificate")
        assertFalse(TrustedApps.isTrusted(TrustedApps.OFFLINE_TRANSLATOR) { false }, "signed by someone else")
    }

    @Test
    fun `a package we never pinned is not trusted whatever it is signed with`() {
        assertFalse(TrustedApps.isTrusted("com.example.browser") { true })
    }

    @Test
    fun `every package the app binds has a full SHA-256 pinned`() {
        assertEquals(setOf(TrustedApps.OFFLINE_TRANSLATOR, TrustedApps.TOR_BROWSER, TrustedApps.TOR_BROWSER_ALPHA), TrustedApps.CERTIFICATES.keys)
        assertTrue(TrustedApps.CERTIFICATES.values.flatten().all { Regex("[0-9a-f]{64}").matches(it) })
    }
}
