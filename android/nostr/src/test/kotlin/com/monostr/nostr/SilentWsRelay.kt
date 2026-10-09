package com.monostr.nostr

import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/**
 * A loopback WebSocket endpoint that completes the handshake and then ignores every frame: enough
 * for rust-nostr to report the relay CONNECTED without any network. It never answers a REQ, unless
 * [answerEose] is set: then every REQ gets an immediate EOSE (an empty relay), and/or [closeWith] is
 * set: then every REQ is refused with `["CLOSED", id, closeWith]` (sent before the EOSE when both are set). With [acceptEvents] every EVENT
 * gets `["OK", id, true, ""]`. [serve]: every REQ gets these events, then EOSE; [requests] records every REQ.
 * With [rejectEvents] every EVENT gets `["OK", id, false, …]`; [events] records every EVENT frame.
 * With [authChallenge] the relay sends `["AUTH", challenge]` on connect and answers every AUTH event with OK; [auths] records them.
 * [authOkDelayMs] delays that OK, [answerAuth] = false never sends it. With [refuseUntilAuth] (strfry's behaviour) every REQ
 * on a connection whose AUTH has not been answered with OK yet gets only `["CLOSED", id, refuseUntilAuth]`.
 * With [rejectAuthWith] every AUTH event is answered with `["OK", id, false, rejectAuthWith]` and the connection never counts as authenticated.
 */
class SilentWsRelay(
    private val answerEose: Boolean = false,
    private val closeWith: String? = null,
    private val acceptEvents: Boolean = false,
    private val serve: List<String> = emptyList(),
    /** Every EVENT gets `["OK", id, false, "blocked: …"]` (a relay that refuses the write). */
    private val rejectEvents: Boolean = false,
    private val authChallenge: String? = null,
    private val refuseUntilAuth: String? = null,
    private val authOkDelayMs: Long = 0,
    private val answerAuth: Boolean = true,
    /** Every AUTH event gets `["OK", id, false, rejectAuthWith]`: a relay that asks for AUTH and can never accept one. */
    private val rejectAuthWith: String? = null,
    /** An EVENT on a connection that has not been authenticated yet gets `["OK", id, false, refuseEventsUntilAuth]` and a fresh AUTH challenge (a relay that wants AUTH for writes). */
    private val refuseEventsUntilAuth: String? = null,
    /** The WebSocket handshake is answered only after this long: the client sees a relay that is still connecting. */
    private val handshakeDelayMs: Long = 0,
    /** Every COUNT gets `["COUNT", id, {"count": countWith}]` (NIP-45); null: a COUNT is refused with CLOSED "unsupported". */
    private val countWith: Long? = null,
    /** A fixed port, to come back where an earlier relay was shut down; 0 picks a free one. */
    port: Int = 0,
) : AutoCloseable {
    private val answers: Boolean get() = answerEose || closeWith != null || acceptEvents || rejectEvents || serve.isNotEmpty() || authChallenge != null || refuseUntilAuth != null || refuseEventsUntilAuth != null || countWith != null
    /** Every COUNT frame the client sent, as received. */
    val counts = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Every AUTH frame the client sent, as received. */
    val auths = java.util.concurrent.CopyOnWriteArrayList<String>()
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Every EVENT frame the client sent, as received. */
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val server = ServerSocket(port, 50, java.net.InetAddress.getLoopbackAddress())
    val port: Int = server.localPort
    val url: String = "ws://127.0.0.1:${server.localPort}"
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
    /** How many connections were accepted so far (a reconnect shows as a second one). */
    val connections: Int get() = sockets.size
    /** Connections the client has not closed yet. */
    val open: Int get() = sockets.count { !it.isClosed }
    private val subscriptions = java.util.concurrent.CopyOnWriteArrayList<Pair<java.io.OutputStream, String>>()

    /** Sends [eventJson] to every subscription made so far, as a relay does with an event that arrives later. */
    fun push(eventJson: String) = subscriptions.forEach { (output, id) -> runCatching { send(output, "[\"EVENT\",\"$id\",$eventJson]") } }

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                thread(isDaemon = true) {
                    runCatching {
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val key = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }
                            .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }!!.substringAfter(':').trim()
                        val accept = Base64.getEncoder().encodeToString(
                            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                        )
                        if (handshakeDelayMs > 0) Thread.sleep(handshakeDelayMs)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                            flush()
                        }
                        if (authChallenge != null) send(socket.getOutputStream(), "[\"AUTH\",\"$authChallenge\"]")
                        if (answers) answerReqs(socket) else while (socket.getInputStream().read() >= 0) { /* discard frames */ }
                    }
                    runCatching { socket.close() }
                }
            }
        }
    }

    override fun close() = server.close()

    /** The relay goes away for good: no new connections, and the open ones are cut (a client sees the disconnect). */
    fun shutdown() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    /** Reads client frames (always masked) and answers REQ and EVENT text frames as configured. */
    private fun answerReqs(socket: java.net.Socket) {
        val input = java.io.DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val authed = java.util.concurrent.atomic.AtomicBoolean(false)
        while (true) {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
            val mask = ByteArray(4).also { if ((b1 and 0x80) != 0) input.readFully(it) }
            val payload = ByteArray(len.toInt()).also { input.readFully(it) }
            if ((b1 and 0x80) != 0) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            if ((b0 and 0x0f) == 0x8) return // close
            if ((b0 and 0x0f) != 0x1) continue // only text frames carry REQs
            val text = String(payload, Charsets.UTF_8)
            val event = Regex("^\\[\\s*\"EVENT\"\\s*,\\s*\\{.*?\"id\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text)?.groupValues?.get(1)
            val req = Regex("^\\[\\s*\"REQ\"\\s*,\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            if (req != null) requests += text
            val count = Regex("^\\[\\s*\"COUNT\"\\s*,\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            if (count != null) {
                counts += text
                send(output, if (countWith != null) "[\"COUNT\",\"$count\",{\"count\":$countWith}]" else "[\"CLOSED\",\"$count\",\"unsupported: this relay does not support NIP-45\"]")
                continue
            }
            if (req != null) subscriptions += output to req
            val auth = Regex("^\\[\\s*\"AUTH\"\\s*,\\s*\\{.*?\"id\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text)?.groupValues?.get(1)
            if (auth != null) auths += text
            if (event != null) events += text
            if (auth != null && answerAuth) {
                val accepted = rejectAuthWith == null
                val ok = if (accepted) "[\"OK\",\"$auth\",true,\"\"]" else "[\"OK\",\"$auth\",false,\"$rejectAuthWith\"]"
                // authenticated before the OK leaves, so a REQ sent in reaction to the OK is served
                if (authOkDelayMs > 0) thread(isDaemon = true) { Thread.sleep(authOkDelayMs); if (accepted) authed.set(true); runCatching { send(output, ok) } }
                else { if (accepted) authed.set(true); send(output, ok) }
            }
            if (req != null && refuseUntilAuth != null && !authed.get()) {
                send(output, "[\"CLOSED\",\"$req\",\"$refuseUntilAuth\"]")
                continue
            }
            if (event != null && refuseEventsUntilAuth != null && !authed.get()) {
                send(output, "[\"OK\",\"$event\",false,\"$refuseEventsUntilAuth\"]")
                send(output, "[\"AUTH\",\"late-challenge\"]")
                continue
            }
            val replies = buildList {
                if (event != null && acceptEvents) add("[\"OK\",\"$event\",true,\"\"]")
                if (event != null && rejectEvents) add("[\"OK\",\"$event\",false,\"blocked: test relay refuses writes\"]")
                if (req != null && closeWith != null) add("[\"CLOSED\",\"$req\",\"$closeWith\"]")
                if (req != null) serve.forEach { add("[\"EVENT\",\"$req\",$it]") }
                if (req != null && (answerEose || serve.isNotEmpty())) add("[\"EOSE\",\"$req\"]")
            }
            for (reply in replies) send(output, reply)
        }
    }

    private fun send(output: java.io.OutputStream, reply: String) {
        val answer = reply.toByteArray(Charsets.UTF_8)
        synchronized(output) {
            output.write(0x81)
            if (answer.size < 126) output.write(answer.size) else { output.write(126); output.write(answer.size shr 8); output.write(answer.size and 0xff) }
            output.write(answer)
            output.flush()
        }
    }
}

/** Polls (in real time) until [check] holds or [timeoutMs] passes. */
suspend fun awaitTrue(timeoutMs: Long = 5000, check: suspend () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (check()) return true
        Thread.sleep(50)
    }
    return check()
}
