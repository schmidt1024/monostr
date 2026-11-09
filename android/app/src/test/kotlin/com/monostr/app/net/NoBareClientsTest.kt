package com.monostr.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Spec 2026-11-06 Tor 7.2: nothing opens a connection past the gate. A failure here is a possible leak of the user's IP: route
 * the new code through the injected client instead of widening a list, and widen one only for code that cannot connect.
 */
class NoBareClientsTest {
    private class Rule(val forbidden: Regex, val allowed: Set<String> = emptySet())

    private val gatedHttp = "app/src/main/kotlin/com/monostr/app/net/GatedHttp.kt"
    private val relayRoute = "nostr/src/main/kotlin/com/monostr/nostr/net/RelayRoute.kt"
    private val linkPreviewFetcher = "app/src/main/kotlin/com/monostr/app/data/linkpreview/LinkPreviewFetcher.kt"
    private val torSwitch = "app/src/main/kotlin/com/monostr/app/net/TorSwitch.kt"
    private val clientBuilders = setOf(
        gatedHttp,
        "nostr/src/main/kotlin/com/monostr/nostr/NostrEngine.kt",
        "nostr/src/main/kotlin/com/monostr/nostr/DetachedSender.kt",
    )

    private val rules = listOf(
        // HTTP or relay clients built by hand
        Rule(Regex("""\bOkHttpClient\(\)"""), clientBuilders),
        Rule(Regex("""\bOkHttpClient\.Builder\(\)"""), clientBuilders),
        Rule(Regex("""HttpURLConnection"""), clientBuilders),
        Rule(Regex("""openConnection\("""), clientBuilders),
        Rule(Regex("""\bClientBuilder\(\)"""), clientBuilders),
        // per-relay options replace the client's proxy, and their default is direct: only the helper builds them (Tor Task 4)
        Rule(Regex("""\bRelayOptions\(|\baddRelayWithOpts\b|\bConnectionMode\b|\bConnection\(\)"""), setOf(relayRoute)),
        // adding a relay decides its route, and a direct one has to be noted for the switch to Tor (F1): only the helpers there add one
        Rule(Regex("""\.add(Read|Write)?Relay\("""), setOf(relayRoute)),
        // rust-nostr parts that open a socket of their own or pick relays by themselves; none is in use
        Rule(Regex("""\bNostrConnect\b|\bNwc\b|\bLocalRelay\b|\bRelayBuilder\b|\baddDiscoveryRelay\b|\bwebsocketTransport\(|\.autoconnect\(|\bGossipOptions\b|\bNostrGossip\b|\brust\.nostr\.sdk\.\*""")),
        // the two rust-nostr clients and their one caller each: a call without the route compiles, so it is caught here
        // a bare rust-nostr client has no route at all; the builders above are the only way to one
        Rule(Regex("""(?<![A-Za-z.])Client\(""")),
        Rule(Regex("""\bNostrEngine\.create\((?!.*socksPort\s*=\s*+(?!null\b))""")),
        Rule(Regex("""(?<!class )\bDetachedSender\((?!.*[gG]ate)""")),
        // Tor's port is ours only while it is connected: the sender asks, a gate that stays unknown refuses anyway (TipsRepository's default)
        Rule(Regex("""(?<!class )\bDetachedSender\((?!.*(torReady|NetworkGate\(\)))""")),
        // the engine reaches for a socket only through dial (Tor Task 6), a pool held for a closed route must not start again; the sender has its own client and ensureRoute
        Rule(Regex("""^(?!.*\b(dial \{|under routeTurn)).*(\bclient\.(connect|tryConnect|connectRelay)\(|\brelay\.connect\()"""), setOf("nostr/src/main/kotlin/com/monostr/nostr/DetachedSender.kt")),
        Rule(Regex("""(?<!class )\bNostrTipsRepository\((?!.*DetachedSender)""")),
        // the gate goes direct only by the deliberate off, which ends everything of Tor first. A test source is not read by this file at all
        Rule(Regex("""\.set\(\s*NetworkMode\.Direct\s*\)"""), setOf(torSwitch)),
        // the parts of a derived client that decide the route; newBuilder() keeps them unless someone replaces them
        Rule(Regex("""\.(proxy|proxySelector|socketFactory|connectionPool|dispatcher)\("""), setOf(gatedHttp)),
        Rule(Regex("""\.dns\("""), setOf(linkPreviewFetcher, gatedHttp)),
        Rule(Regex("""\bDns\.SYSTEM\b"""), setOf(gatedHttp)),
        // anything else that reaches the network by itself
        Rule(Regex("""\bURLConnection\b|\bjava\.net\.URL\b|\bURL\(|\.toURL\(|\.openStream\(""")),
        Rule(Regex("""(?<![A-Za-z])Socket\(|\bDatagramSocket\b|\bSSLSocket\b|\bInetSocketAddress\("""), setOf(gatedHttp)),
        Rule(Regex("""\bDownloadManager\b|\bWebView\b|\bCronetEngine\b|\bisReachable\(""")),
        // literals only (each is parsed, not resolved)
        Rule(Regex("""InetAddress\.getByName\("""), setOf(
            "nostr/src/main/kotlin/com/monostr/nostr/net/NetworkGate.kt",
            "tips/src/main/kotlin/com/monostr/tips/RelayHosts.kt",
            "app/src/main/kotlin/com/monostr/app/data/linkpreview/AddressPolicy.kt",
            linkPreviewFetcher,
        )),
        Rule(Regex("""InetAddress\.getAllByName\(|InetAddress\.getLocalHost\(""")),
        // Coil and media3 fall back to a client or stack of their own when they are not given one
        Rule(Regex("""OkHttpNetworkFetcherFactory\(\)""")),
        Rule(Regex("""\bImageLoader\(""")),
        Rule(Regex("""ImageLoader\.Builder\((?!.*callFactory)""")),
        Rule(Regex("""ExoPlayer\.Builder\((?!.*setMediaSourceFactory)""")),
        Rule(Regex("""\bDefaultHttpDataSource\b|\bDefaultDataSource\b|\bUdpDataSource\b""")),
        Rule(Regex("""\bDefaultMediaSourceFactory\("""), setOf("app/src/main/kotlin/com/monostr/app/ui/media/VideoPlayerScreen.kt")),
        // the platform's players and the retriever open a URL on their own; the retriever here only reads a picked local video
        Rule(Regex("""\bMediaPlayer\b|\bVideoView\b""")),
        Rule(Regex("""\.setDataSource\("""), setOf("app/src/main/kotlin/com/monostr/app/media/VideoPreparer.kt")),
        // only the gate's factory opens plain sockets, and it asks for them without this spelling
        Rule(Regex("""SocketFactory\.getDefault""")),
    )

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun sources(): List<File> = listOf("app", "nostr", "tips", "monero")
        .flatMap { File(root, "$it/src/main").walkTopDown().filter { f -> f.extension == "kt" || f.extension == "java" }.toList() }

    private fun isComment(line: String) = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** [path] relative to the project root, so an allowed name elsewhere does not count. */
    private fun violations(rule: Rule, path: String, lines: List<String>) =
        if (path in rule.allowed) emptyList()
        else lines.withIndex().filter { (_, l) -> !isComment(l) && rule.forbidden.containsMatchIn(l) }.map { (i, l) -> "$path:${i + 1}: ${l.trim()}" }

    @Test
    fun `nothing connects past the gate`() {
        val all = sources()
        assertTrue(all.size > 100, "found only ${all.size} sources under $root")
        val found = all.flatMap { f -> val lines = f.readLines(); rules.flatMap { violations(it, f.relativeTo(root).path, lines) } }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `the allow-list means that path, not that file name`() {
        val line = listOf("val c = OkHttpClient()")
        val rule = rules.first { it.forbidden.containsMatchIn(line[0]) }
        assertTrue(violations(rule, gatedHttp, line).isEmpty())
        for (elsewhere in listOf("GatedHttp.kt", "app/src/main/kotlin/com/monostr/app/ui/GatedHttp.kt", "tips/$gatedHttp")) {
            assertTrue(violations(rule, elsewhere, line).isNotEmpty(), elsewhere)
        }
    }

    @Test
    fun `only the switch moves the gate to direct`() {
        val line = listOf("gate.set(NetworkMode.Direct)")
        val rule = rules.first { it.forbidden.containsMatchIn(line[0]) }
        assertTrue(violations(rule, torSwitch, line).isEmpty())
        assertTrue(violations(rule, "app/src/main/kotlin/com/monostr/app/net/AppTorUi.kt", line).isNotEmpty())
        // moving it to Tor is not what this rule is for
        assertTrue(violations(rule, "Some.kt", listOf("gate.set(tor)", "gate.set(NetworkMode.Tor(1, 2))")).isEmpty())
    }

    @Test
    fun `every allowed path exists`() {
        val missing = rules.flatMap { it.allowed }.distinct().filterNot { File(root, it).isFile }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the rules leave what they are not for`() {
        fun caught(line: String) = rules.any { violations(it, "Some.kt", listOf(line)).isNotEmpty() }
        listOf(
            "val c: Client = engine.client",
            "class WatcherClient(http: Http)",
            "val w = WatcherClient(http)",
            "val b = BlossomClient(http, signer)",
            "internal val client: Client,",
            "private val socksPort: Int? = null,",
            "val e = NostrEngine.create(dir, signer, relays, socksPort = port)",
            "val e = NostrEngine.create(dir, signer, relays, socksPort = (mode as? NetworkMode.Tor)?.sessionPort)",
            "val e = NostrEngine.create(dir, signer, relays, socksPort = nullablePort)",
            "dial { ignoringErrors { client.connectRelay(url) } }",
            "dial { runCatching { client.tryConnect(RECONNECT_WAIT) } }",
            "ignoringErrors { client.connect() } // under routeTurn: a dial would wait for itself",
            "val s = DetachedSender(engine.ffiDispatcher, networkGate, torReady = torConnected(tor))",
        ).forEach { assertTrue(!caught(it), it) }
    }

    @Test
    fun `the rules catch what they are for`() {
        fun caught(line: String) = rules.any { violations(it, "Some.kt", listOf(line)).isNotEmpty() }
        listOf(
            "val c = OkHttpClient()",
            "client.addRelayWithOpts(url, opts)",
            "val o = RelayOptions().read(true)",
            "val o = opts.connectionMode(ConnectionMode.Direct)",
            "val n = NostrConnect(uri, keys, timeout, null)",
            "val w = Nwc(uri)",
            "client.addDiscoveryRelay(url)",
            "import rust.nostr.sdk.*",
            "val e = NostrEngine.create(dir, signer, relays)",
            "val e = NostrEngine.create(dir, signer, relays, socksPort = null)",
            "val c = Client()",
            "val c = Client(signer)",
            "val o = options.connection(Connection())",
            "val r = LocalRelay(builder)",
            "val r = RelayBuilder()",
            "gate.set(NetworkMode.Direct)",
            "networkGate.set( NetworkMode.Direct )",
            "builder.websocketTransport(t)",
            "options.autoconnect(true)",
            "options.gossip(GossipOptions())",
            "builder.gossip(NostrGossip.inMemory())",
            "val s = DetachedSender(ffi)",
            "val s = DetachedSender(ffi, gate)",
            "client.connect()",
            "ignoringErrors { client.connectRelay(url) }",
            "client.tryConnect(timeout)",
            "relay.connect()",
            "val t = NostrTipsRepository(engine, gate = startup)",
            "val c = OkHttpClient.Builder().build()",
            "http.newBuilder().proxy(Proxy.NO_PROXY).build()",
            "http.newBuilder().socketFactory(f).build()",
            "add(OkHttpNetworkFetcherFactory())",
            "ImageLoader.Builder(context).build()",
            "ExoPlayer.Builder(context).build()",
            "Socket(host, 80)",
            "URL(u).readBytes()",
            "val loader = ImageLoader(context)",
            "DefaultMediaSourceFactory(context)",
            "SocketFactory.getDefault().createSocket()",
            "val p = MediaPlayer()",
            "val v = VideoView(context)",
            "retriever.setDataSource(url, emptyMap())",
        ).forEach { assertTrue(caught(it), it) }
        listOf("class X(val loader: ImageLoader)", "override fun newImageLoader(context: PlatformContext): ImageLoader =", "SingletonImageLoader.get(context)")
            .forEach { assertTrue(!caught(it), it) }
        listOf("ServerSocket(0)", "URLEncoder.encode(s)", "// OkHttpClient()", "ExoPlayer.Builder(c).setMediaSourceFactory(f).build()")
            .forEach { assertTrue(!caught(it), it) }
    }
}
