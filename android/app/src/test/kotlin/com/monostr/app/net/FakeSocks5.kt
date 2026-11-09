package com.monostr.app.net

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A small SOCKS5 server on loopback that records what it is asked. With [relayToPort] every connect is carried to that local
 * port whatever the name, so a name that exists nowhere still gets an answer: a local DNS lookup would fail the test instead.
 * Without it every connect is refused.
 */
class FakeSocks5(private val relayToPort: Int? = null) : Closeable {
    data class Ask(val atyp: Int, val host: String, val port: Int)

    private val listener = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int get() = listener.localPort
    val asks = CopyOnWriteArrayList<Ask>()

    init {
        thread(isDaemon = true, name = "fake-socks") {
            while (!listener.isClosed) {
                val s = runCatching { listener.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        check(input.readUnsignedByte() == 5)
        repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
        out.write(byteArrayOf(5, 0)); out.flush()
        check(input.readUnsignedByte() == 5)
        check(input.readUnsignedByte() == 1) // CONNECT
        input.readUnsignedByte()
        val atyp = input.readUnsignedByte()
        val host = when (atyp) {
            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress!!
            else -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress!!
        }
        asks += Ask(atyp, host, input.readUnsignedShort())
        val target = relayToPort
        if (target == null) {
            out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush() // connection refused
            return
        }
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), target).use { up ->
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            val back = thread(isDaemon = true) { runCatching { up.getInputStream().copyTo(out) }; runCatching { s.close() } }
            runCatching { input.copyTo(up.getOutputStream()) }
            back.join(2_000)
        }
    }

    override fun close() = listener.close()
}
