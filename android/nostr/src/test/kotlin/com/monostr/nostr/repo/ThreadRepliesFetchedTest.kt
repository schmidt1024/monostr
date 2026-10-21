package com.monostr.nostr.repo

import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.untilFirst
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import java.nio.file.Path

/** Spec perf2 A1: the view after the `#e` replies fetch says so, for the `thread relay replies shown` mark. */
class ThreadRepliesFetchedTest {
    @TempDir lateinit var dir: Path
    private val alice = Keys.generate()

    @Test
    fun `only the view after the relay fetch of the replies says the replies were fetched`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val root = EventBuilder.textNote("root").signWithKeys(alice).also { e.save(it) }
                // focused = root, no ancestors: the local view, then the one after the fetch
                val views = NostrThreadRepository(e).observe(root.id().toHex()).untilFirst { it.repliesFetched }
                assertFalse(views[0].repliesFetched, "the local view comes before any fetch")
                assertTrue(views.last().repliesFetched)
            } finally {
                e.close()
            }
        }
    }
}
