package com.monostr.nostr

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Backlog 36: "replace with my published list" must take the list as the relays have it, not an older stored one. */
class OwnRelayListFreshTest {
    @TempDir lateinit var dir: Path
    private val sk = "0000000000000000000000000000000000000000000000000000000000000001"
    private val keys = Keys.parse(sk)

    private fun list(at: Long, vararg relays: String) =
        EventBuilder(Kind(10002u), "").tags(relays.map { Tag.parse(listOf("r", it)) })
            .customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    @Test
    fun `fresh asks the relays and takes a newer list than the stored one, the default stays local`() = runTest {
        val newer = list(2000, "wss://new.example")
        SilentWsRelay(serve = listOf(newer.asJson())).use { relay ->
            val e = connectedEngine(dir, relay, sk)
            try {
                e.save(list(1000, "wss://old.example"))
                assertEquals(listOf("wss://old.example"), e.ownRelayList()!!.read)
                assertEquals(listOf("wss://new.example"), e.ownRelayList(fresh = true)!!.read)
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `fresh keeps the stored list when the relays only have an older one`() = runTest {
        SilentWsRelay(serve = listOf(list(1000, "wss://old.example").asJson())).use { relay ->
            val e = connectedEngine(dir, relay, sk)
            try {
                e.save(list(2000, "wss://new.example"))
                assertEquals(listOf("wss://new.example"), e.ownRelayList(fresh = true)!!.read)
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a list only the index relays have is found, fresh or not, and without them it is none`() = runTest {
        val published = list(1500, "wss://mom.example")
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay(answerEose = true, serve = listOf(published.asJson())).use { index ->
                val e = connectedEngine(dir, own, sk)
                try {
                    assertNull(e.ownRelayList(fresh = true), "the session's relays do not have it")
                    assertEquals(listOf("wss://mom.example"), e.ownRelayList(fresh = true, extra = listOf(index.url))!!.read)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `the stored path asks the index relays too when the session's relays have nothing`() = runTest {
        val published = list(1500, "wss://mom.example")
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay(answerEose = true, serve = listOf(published.asJson())).use { index ->
                val e = connectedEngine(dir, own, sk)
                try {
                    assertEquals(listOf("wss://mom.example"), e.ownRelayList(extra = listOf(index.url))!!.write)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `the index relays are not asked when the session's relays have the list`() = runTest {
        SilentWsRelay(serve = listOf(list(1500, "wss://own.example").asJson())).use { own ->
            SilentWsRelay(answerEose = true, recordRequests = true).use { index ->
                val e = connectedEngine(dir, own, sk)
                try {
                    assertEquals(listOf("wss://own.example"), e.ownRelayList(fresh = true, extra = listOf(index.url))!!.read)
                    assertEquals(emptyList<String>(), index.requests.toList(), "no extra connection for a list found at home")
                } finally {
                    e.close()
                }
            }
        }
    }
}
