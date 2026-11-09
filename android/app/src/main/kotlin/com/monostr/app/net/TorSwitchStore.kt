package com.monostr.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The device-wide Tor switch, kept as a marker file instead of a DataStore key: a corrupt or unreadable DataStore reads as
 * empty, which would silently mean «off» for someone who turned Tor on. Here the file's presence is «on», and anything that
 * keeps us from looking reads as «on» (fail closed). Not touched by logout; absent by default.
 */
class TorSwitchStore(
    private val marker: File,
    private val syncFile: (File) -> Unit = { f -> FileOutputStream(f, true).use { it.fd.sync() } },
    private val syncDirectory: (File) -> Unit = ::syncDirectoryOf,
) {
    private val state = MutableStateFlow(readAtStart())
    val enabled: StateFlow<Boolean> = state
    private val writing = Mutex()

    /** One stat call: the file exists = on; missing = off; any other failure (parent is a file, permissions, ...) = on. */
    fun readAtStart(): Boolean = try {
        Files.readAttributes(marker.toPath(), BasicFileAttributes::class.java)
        true
    } catch (_: NoSuchFileException) {
        false
    } catch (_: Exception) {
        true
    }

    /** Turning on writes the file first; the state follows the file. Throws [IOException] when the file cannot be changed. */
    suspend fun set(value: Boolean) = writing.withLock {
        // file and state stay together: a cancelled caller must not leave the file changed and the state behind
        withContext(Dispatchers.IO + NonCancellable) {
            if (value) {
                marker.parentFile?.mkdirs()
                if (!marker.exists()) {
                    if (!marker.createNewFile()) throw IOException("cannot create ${marker.name}")
                    // the process may end right after this (TorSwitch), and a power cut should not take the choice with it
                    try {
                        syncFile(marker)
                    } catch (e: IOException) {
                        // not known to last: file and state say the same, and nothing changed for the caller
                        marker.delete()
                        throw e
                    }
                    // its name in the directory; the file itself is what counts, so this one is best effort
                    marker.parentFile?.let { dir -> runCatching { syncDirectory(dir) } }
                }
            } else if (!marker.delete() && readAtStart()) {
                throw IOException("cannot delete ${marker.name}")
            }
            state.value = value
        }
    }
}

/** Linux allows an fsync on a directory opened for reading. */
internal fun syncDirectoryOf(dir: File) {
    FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
