package com.monostr.app

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Another app on a port Tor would use: counts who connects, and since 2026-11-26 (backlog 118: «the squatter heard from
 * us» in full runs only) also says who. It answers the SOCKS5 greeting, reads the request and notes the target and the
 * time, so a failure names the relay or site that was dialled; nothing is ever relayed.
 */
class TorSquatter(private val port: Int) : AutoCloseable {
    private val listener = ServerSocket(port, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val began = System.currentTimeMillis()
    val accepted = AtomicInteger(0)
    val heard = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                val s = runCatching { listener.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                heard += "port $port +${System.currentTimeMillis() - began} ms: " + runCatching { target(s) }.getOrElse { "unread (${it.javaClass.simpleName})" } +
                    // who is alive at this very moment (MonostrTestRunner sets trackLive)
                    " | engines at ${System.currentTimeMillis()}: " + runCatching { com.monostr.nostr.NostrEngine.liveReport().joinToString(" || ") }.getOrElse { "unread: $it" } +
                    " | trace: " + runCatching { com.monostr.nostr.NostrEngine.traceReport().joinToString(" ;; ") }.getOrElse { "unread: $it" }
                runCatching { s.close() }
            }
        }
    }

    /** The CONNECT target of a SOCKS5 client: no-auth is accepted, the request read, then the line is dropped. */
    private fun target(s: Socket): String {
        s.soTimeout = 2_000
        val input = s.getInputStream()
        val version = input.read()
        if (version != 5) return "not SOCKS5 (first byte $version)"
        val methods = input.read()
        repeat(methods) { input.read() }
        s.getOutputStream().apply { write(byteArrayOf(5, 0)); flush() }
        val head = ByteArray(4).also { input.readNBytesCompat(it) }
        val host = when (head[3].toInt()) {
            3 -> ByteArray(input.read()).also { input.readNBytesCompat(it) }.toString(Charsets.US_ASCII)
            1 -> ByteArray(4).also { input.readNBytesCompat(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
            4 -> "ipv6"
            else -> "address type ${head[3]}"
        }
        val portBytes = ByteArray(2).also { input.readNBytesCompat(it) }
        return "$host:${((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)}"
    }

    private fun java.io.InputStream.readNBytesCompat(into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = read(into, off, into.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    override fun close() = listener.close()
}
