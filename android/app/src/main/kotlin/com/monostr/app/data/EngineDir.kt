package com.monostr.app.data

import java.io.File

/**
 * Where an account's event database lives inside its folder ([accountDir]).
 *
 * rust-nostr keeps a database open for as long as anything of its client is alive, which is longer than the session: an account
 * removed and added again in one run of the app (owner 2026-11-27: logged out everywhere, then back in) got the very database
 * that had just been deleted. It read the removed account's events and wrote into files that no longer had a name, so the next
 * start of the app found nothing on disk. A folder that was removed therefore never gets its database at the same path again:
 * a new account folder puts it into a subfolder with a random name and notes that name in [MARKER]. An account from before this
 * (its `data.mdb` lies in the folder itself) stays where it is. A folder without a database but with other files is what that
 * fault left behind after a restart: those files go, they belong to the database that is gone.
 */
object EngineDir {
    const val MARKER = "db"
    private const val LEGACY_FILE = "data.mdb"
    private val NAME = Regex("db-[0-9a-f]{16}")

    fun of(accountDir: File, fresh: () -> String = { "db-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16) }): File {
        accountDir.mkdirs()
        val marker = File(accountDir, MARKER)
        val noted = runCatching { marker.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { NAME.matches(it) }
        if (noted != null) return File(accountDir, noted).apply { mkdirs() }
        if (File(accountDir, LEGACY_FILE).exists()) return accountDir
        // whatever else lies here (sync marks, relay-seen, the feed snapshot) describes a database that is gone: with it, a fresh
        // database would be taken for one that is synced up to the marks
        accountDir.listFiles()?.forEach { it.deleteRecursively() }
        val name = fresh()
        require(NAME.matches(name)) { "not a database folder name" }
        val dir = File(accountDir, name).apply { mkdirs() }
        marker.writeText(name)
        return dir
    }
}
