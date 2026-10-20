package com.monostr.nostr

import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path

/** An engine whose only relay is the loopback [relay], connected; the caller closes it. */
suspend fun connectedEngine(
    dir: Path,
    relay: SilentWsRelay,
    secretKeyHex: String = "0000000000000000000000000000000000000000000000000000000000000001",
): NostrEngine {
    val engine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(secretKeyHex), listOf(relay.url))
    engine.connect()
    assertTrue(awaitTrue { engine.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
    return engine
}
