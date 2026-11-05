package com.monostr.nostr.model

import com.monostr.nostr.RelayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-11-05 relay read/write. */
class RelayModesTest {
    @Test fun `a one way relay carries its marker, both carries none`() {
        assertEquals(listOf("r", "wss://a"), RelayModes.tag(RelayEntry("wss://a/", RelayMode.BOTH)))
        assertEquals(listOf("r", "wss://a", "read"), RelayModes.tag(RelayEntry("wss://a", RelayMode.READ)))
        assertEquals(listOf("r", "wss://a", "write"), RelayModes.tag(RelayEntry("wss://a", RelayMode.WRITE)))
    }

    @Test fun `the minimum needs a reader and a writer, no stored mode counts as both`() {
        assertTrue(RelayModes.valid(listOf("a"), emptyMap()))
        assertFalse(RelayModes.valid(listOf("a"), mapOf("a" to RelayMode.READ)))
        assertTrue(RelayModes.valid(listOf("a", "b"), mapOf("a" to RelayMode.READ, "b" to RelayMode.WRITE)))
        assertFalse(RelayModes.valid(listOf("a", "b"), mapOf("a" to RelayMode.WRITE, "b" to RelayMode.WRITE)))
    }

    @Test fun `adopting keeps markers, a list without a reader or writer ignores them`() {
        val ok = RelayModes.adopt(RelayList(read = listOf("a", "c"), write = listOf("b", "c")))
        assertEquals(mapOf("a" to RelayMode.READ, "b" to RelayMode.WRITE, "c" to RelayMode.BOTH), ok.modes)
        assertFalse(ok.markersIgnored)
        val broken = RelayModes.adopt(RelayList(read = emptyList(), write = listOf("a", "b")))
        assertEquals(listOf("a", "b"), broken.relays)
        assertEquals(emptyMap<String, RelayMode>(), broken.modes)
        assertTrue(broken.markersIgnored)
    }

    @Test fun `publishing keeps a published marker for a relay with no chosen mode, a chosen mode wins`() {
        val published = RelayList(read = listOf("a"), write = listOf("b", "c"))
        assertEquals(
            listOf(RelayEntry("a", RelayMode.READ), RelayEntry("b", RelayMode.BOTH), RelayEntry("d", RelayMode.BOTH)),
            RelayModes.forPublish(listOf("a", "b", "d"), mapOf("b" to RelayMode.BOTH), published),
        )
    }

    @Test fun `a trailing slash is the same relay`() {
        assertEquals(listOf(RelayEntry("wss://a", RelayMode.READ)), RelayModes.forPublish(listOf("wss://a/"), emptyMap(), RelayList(listOf("wss://a"), emptyList())))
    }

    @Test fun `repair drops modes of URLs outside the list and keeps a valid rest`() {
        val modes = mapOf("a" to RelayMode.READ, "b" to RelayMode.WRITE, "x" to RelayMode.WRITE)
        assertEquals(mapOf("a" to RelayMode.READ, "b" to RelayMode.WRITE), RelayModes.repair(listOf("a", "b"), modes))
        assertEquals(emptyMap<String, RelayMode>(), RelayModes.repair(listOf("a"), emptyMap()))
    }

    @Test fun `repair relaxes only one relay when no reader or no writer is left`() {
        // no writer: the first read-only relay becomes both, the others stay
        assertEquals(
            mapOf("a" to RelayMode.BOTH, "b" to RelayMode.READ),
            RelayModes.repair(listOf("a", "b"), mapOf("a" to RelayMode.READ, "b" to RelayMode.READ, "c" to RelayMode.WRITE)),
        )
        // no reader: the first write-only relay becomes both
        assertEquals(
            mapOf("a" to RelayMode.BOTH, "b" to RelayMode.WRITE),
            RelayModes.repair(listOf("a", "b"), mapOf("a" to RelayMode.WRITE, "b" to RelayMode.WRITE)),
        )
        // a relay without a mode counts as both already, so nothing to relax
        assertEquals(mapOf("a" to RelayMode.READ), RelayModes.repair(listOf("a", "b"), mapOf("a" to RelayMode.READ)))
        val fixed = RelayModes.repair(listOf("a", "b", "c"), mapOf("a" to RelayMode.WRITE, "b" to RelayMode.WRITE, "c" to RelayMode.WRITE))
        assertTrue(RelayModes.valid(listOf("a", "b", "c"), fixed))
    }
}
