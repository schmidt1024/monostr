package com.monostr.app

import com.monostr.app.session.recordRelaysSeen
import com.monostr.nostr.SyncMarks
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 0.24.3: when each relay of the list was last seen connected, for "last connected: 2 d" in the settings. */
class RelaySeenTest {
    @TempDir lateinit var dir: File

    @Test
    fun `connected relays are recorded, at most once per ten minutes, others keep their time`() = runTest {
        val marks = SyncMarks(File(dir, "relay-seen.json"))
        val states = listOf(
            RelayInfo("wss://a/", RelayState.CONNECTED),
            RelayInfo("wss://b", RelayState.DISCONNECTED),
            RelayInfo("wss://c", RelayState.CONNECTING),
        )
        marks.put("wss://b", 500)
        recordRelaysSeen(states, marks, now = 1_000)
        assertEquals(mapOf("wss://a" to 1_000L, "wss://b" to 500L), marks.all())
        recordRelaysSeen(states, marks, now = 1_000 + 599)
        assertEquals(1_000L, marks.all()["wss://a"])
        recordRelaysSeen(states, marks, now = 1_000 + 600)
        assertEquals(1_600L, marks.all()["wss://a"])
    }
}
