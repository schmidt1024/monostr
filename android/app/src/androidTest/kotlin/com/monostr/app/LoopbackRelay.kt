package com.monostr.app

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A relay on 127.0.0.1 inside the test process (same idea as `SilentWsRelay` in the :nostr JVM
 * tests): every REQ gets the [serve]d events and EOSE, every EVENT gets `["OK", id, true, ""]` and
 * is recorded in [events]; every REQ frame is recorded in [reqs]. With [silent] no REQ is ever answered (a relay that hangs). With
 * [authChallenge] every connection gets `["AUTH", challenge]` right after the handshake (as strfry sends it) and each AUTH event is
 * answered with OK and recorded in [auths] with its connection. [open] holds the connections not closed yet. Nothing leaves the emulator.
 */
class LoopbackRelay(
    private val serve: List<String> = emptyList(),
    private val silent: Boolean = false,
    private val authChallenge: String? = null,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val url: String = "ws://127.0.0.1:${server.localPort}"
    val events = CopyOnWriteArrayList<String>()
    /** Every `REQ` frame as received, in order. */
    val reqs = CopyOnWriteArrayList<String>()

    /** An AUTH event's pubkey, with the number of the connection it came on. */
    data class Auth(val connection: Int, val pubkey: String)
    val auths = CopyOnWriteArrayList<Auth>()
    /** Connections accepted so far, numbered from 1. */
    val connections = AtomicInteger()
    /** The numbers of the connections still open. */
    val open: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val id = connections.incrementAndGet()
                open += id
                thread(isDaemon = true) { runCatching { handle(socket, id) }; runCatching { socket.close() }; open -= id }
            }
        }
    }

    override fun close() = server.close()

    private fun handle(socket: Socket, id: Int) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val key = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }
            .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        out.flush()
        authChallenge?.let { send(out, "[\"AUTH\",\"$it\"]") }
        val data = java.io.DataInputStream(socket.getInputStream())
        while (true) {
            val b0 = data.readUnsignedByte()
            val b1 = data.readUnsignedByte()
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = data.readUnsignedShort().toLong() else if (len == 127L) len = data.readLong()
            val mask = ByteArray(4).also { if ((b1 and 0x80) != 0) data.readFully(it) }
            val payload = ByteArray(len.toInt()).also { data.readFully(it) }
            if ((b1 and 0x80) != 0) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            if ((b0 and 0x0f) == 0x8) return
            if ((b0 and 0x0f) != 0x1) continue
            val text = String(payload, Charsets.UTF_8)
            val event = Regex("^\\[\\s*\"EVENT\"\\s*,\\s*\\{.*?\"id\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text)?.groupValues?.get(1)
            val req = Regex("^\\[\\s*\"REQ\"\\s*,\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            val auth = Regex("^\\[\\s*\"AUTH\"\\s*,\\s*\\{.*?\"id\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text)?.groupValues?.get(1)
            if (auth != null) {
                Regex("\"pubkey\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text)?.groupValues?.get(1)?.let { auths += Auth(id, it) }
                send(out, "[\"OK\",\"$auth\",true,\"\"]")
            }
            if (event != null) {
                events += text
                send(out, "[\"OK\",\"$event\",true,\"\"]")
            }
            if (req != null) reqs += text
            if (req != null && !silent) {
                serve.forEach { send(out, "[\"EVENT\",\"$req\",$it]") }
                send(out, "[\"EOSE\",\"$req\"]")
            }
        }
    }

    private fun send(out: java.io.OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(out) {
            out.write(0x81)
            when {
                bytes.size < 126 -> out.write(bytes.size)
                bytes.size < 65_536 -> { out.write(126); out.write(bytes.size shr 8); out.write(bytes.size and 0xff) }
                else -> { out.write(127); repeat(4) { out.write(0) }; for (s in 24 downTo 0 step 8) out.write((bytes.size shr s) and 0xff) }
            }
            out.write(bytes)
            out.flush()
        }
    }
}
