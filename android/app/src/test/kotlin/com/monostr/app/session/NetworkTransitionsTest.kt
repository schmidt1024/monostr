package com.monostr.app.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NetworkTransitionsTest {
    private var now = 100_000L
    private val t = NetworkTransitions(nowMs = { now }).apply { onRegistered() }

    @Test
    fun `first available after register is ignored`() {
        assertNull(t.onAvailable(WIFI))
    }

    @Test
    fun `available after lost is RETURNED`() {
        t.onAvailable(WIFI)
        now += 10_000
        t.onLost(WIFI)
        now += 10_000
        assertEquals(NetworkChange.RETURNED, t.onAvailable(WIFI))
    }

    @Test
    fun `other network without lost is SWITCHED`() {
        t.onAvailable(WIFI)
        now += 10_000
        assertEquals(NetworkChange.SWITCHED, t.onAvailable(MOBILE))
    }

    @Test
    fun `same network again is ignored`() {
        t.onAvailable(WIFI)
        now += 10_000
        assertNull(t.onAvailable(WIFI))
    }

    @Test
    fun `two changes within five seconds give one`() {
        t.onAvailable(WIFI)
        now += 10_000
        assertEquals(NetworkChange.SWITCHED, t.onAvailable(MOBILE))
        now += 4_999
        assertNull(t.onAvailable(WIFI))
        now += 1
        assertEquals(NetworkChange.SWITCHED, t.onAvailable(MOBILE))
    }

    @Test
    fun `available after lost on another network is SWITCHED`() {
        t.onAvailable(WIFI)
        now += 10_000
        t.onLost(WIFI)
        now += 10_000
        assertEquals(NetworkChange.SWITCHED, t.onAvailable(MOBILE))
    }

    @Test
    fun `lost of a network that is not the current one is ignored`() {
        t.onAvailable(WIFI)
        now += 10_000
        t.onLost(MOBILE)
        now += 10_000
        assertNull(t.onAvailable(WIFI))
    }

    @Test
    fun `a debounced change still updates the current network`() {
        t.onAvailable(WIFI)
        now += 10_000
        assertEquals(NetworkChange.SWITCHED, t.onAvailable(MOBILE))
        now += 1_000
        assertNull(t.onAvailable(WIFI))
        now += 10_000
        assertNull(t.onAvailable(WIFI))
    }

    @Test
    fun `after registering again another network is SWITCHED`() {
        t.onAvailable(WIFI)
        t.onRegistered()
        now += 10_000
        assertEquals(NetworkChange.SWITCHED, t.onAvailable(MOBILE))
    }

    @Test
    fun `after registering again the same network is ignored`() {
        t.onAvailable(WIFI)
        now += 10_000
        t.onLost(WIFI)
        t.onRegistered()
        now += 10_000
        assertNull(t.onAvailable(WIFI))
        now += 10_000
        assertEquals(NetworkChange.SWITCHED, t.onAvailable(MOBILE))
    }

    private companion object {
        const val WIFI = 101L
        const val MOBILE = 202L
    }
}
