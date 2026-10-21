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

/** An engine whose relays are the loopback [relays], every one of them connected; the caller closes it (spec wave 2 §6). */
suspend fun connectedEngine(
    dir: Path,
    relays: List<SilentWsRelay>,
    secretKeyHex: String = "0000000000000000000000000000000000000000000000000000000000000001",
): NostrEngine {
    val engine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(secretKeyHex), relays.map { it.url })
    engine.connect()
    assertTrue(awaitTrue { engine.connectedNormalRelayUrls().size == relays.size }, "loopback relays never all connected")
    return engine
}
