package com.monostr.app.data

import com.monostr.nostr.repo.CachedList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileOwnListsCacheTest {
    @TempDir lateinit var dir: File
    private val me = "e".repeat(64)
    private val list = CachedList("30000:$me:abc", "1".repeat(64), "Friends", null, listOf("b".repeat(64)), true, 1000)

    @Test
    fun `the summary is kept per account in the account's directory`() = runTest {
        val cache = FileOwnListsCache(dir, UnconfinedTestDispatcher(testScheduler))
        assertNull(cache.load(me))
        cache.save(me, listOf(list))
        assertEquals(listOf(list), cache.load(me))
        assertTrue(File(accountDir(dir, me), FileOwnListsCache.FILE).exists())
        assertNull(cache.load("f".repeat(64)))
    }

    @Test
    fun `logout removes it, and a write still pending from that session does not bring it back`() = runTest {
        // Review Focus: the next account must not read this one's lists, private members included
        val cache = FileOwnListsCache(dir, UnconfinedTestDispatcher(testScheduler))
        cache.save(me, listOf(list))
        cache.remove(me)
        cache.save(me, listOf(list)) // the old session's reconcile, finishing late
        assertNull(cache.load(me))
        cache.save(me, listOf(list)) // the account's next session read first, so it writes again
        assertEquals(listOf(list), cache.load(me))
    }

    @Test
    fun `a logout while a save is writing leaves no file behind`() = runTest {
        val cache = FileOwnListsCache(dir, UnconfinedTestDispatcher(testScheduler))
        cache.beforeCommit = { kotlinx.coroutines.runBlocking { cache.remove(me) } }
        cache.save(me, listOf(list))
        assertEquals(emptyList<String>(), accountDir(dir, me).list()!!.toList())
        cache.beforeCommit = {}
        assertNull(cache.load(me))
    }

    @Test
    fun `a late collection of the old session after logout leaves no summary file`() = runTest {
        // Task 5 ruling: a collector of the old session (on a screen's scope) may still load and persist after
        // logout; its load would clear the removed mark and its save bring the decrypted private members back
        val cache = FileOwnListsCache(dir, UnconfinedTestDispatcher(testScheduler))
        val old = SessionOwnListsCache(cache)
        old.save(me, listOf(list))
        old.close() // logout: the old session's view closes before the file goes
        cache.remove(me)
        assertNull(old.load(me)) // the old session's late start load
        old.save(me, listOf(list)) // and its persist behind it
        assertFalse(File(accountDir(dir, me), FileOwnListsCache.FILE).exists())
        // the account's next session reads and writes as before
        val next = SessionOwnListsCache(cache)
        assertNull(next.load(me))
        next.save(me, listOf(list))
        assertEquals(listOf(list), next.load(me))
    }

    @Test
    fun `a broken file counts as no summary`() = runTest {
        val cache = FileOwnListsCache(dir, UnconfinedTestDispatcher(testScheduler))
        accountDir(dir, me).mkdirs()
        File(accountDir(dir, me), FileOwnListsCache.FILE).writeText("{broken")
        assertNull(cache.load(me))
    }
}
