package com.monostr.nostr

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
}
