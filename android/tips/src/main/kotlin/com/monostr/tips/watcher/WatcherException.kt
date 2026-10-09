package com.monostr.tips.watcher

import java.io.IOException

sealed class WatcherException(message: String) : Exception(message) {
    /** The watcher could not be reached. */
    class Network(cause: IOException) : WatcherException("watcher unreachable: ${cause.message}") {
        init {
            initCause(cause)
        }
    }

    /** The watcher answered with a non-2xx status. [message] is the server's `error` field or "HTTP <status>". */
    class Http(val status: Int, message: String) : WatcherException(message)

    /** The watcher answered 2xx but the body was not what the protocol defines. */
    class Protocol(message: String) : WatcherException(message)
}
