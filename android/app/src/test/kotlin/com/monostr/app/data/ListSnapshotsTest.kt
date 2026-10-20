package com.monostr.app.data

import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec perf 3.7: a feed snapshot per pinned list, gone on unpin and logout. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListSnapshotsTest {
    @TempDir lateinit var dir: File
    private val me = "e".repeat(64)
    private val friends = ListAddress(30000, me, "friends")
    private val odd = ListAddress(30000, me, "a/b c:d")
    private fun item() = FeedSnapshot.Item(Note("1".repeat(64), me, "hi", 1000, 1, null, null, null, emptyList()), Profile.empty(me), Profile.empty(me))
    private fun file(address: ListAddress) = File(dir, "${ListSnapshots.DIR}/${ListSnapshots.fileName(address)}")

    @Test
    fun `each pinned list has its own file, also with an odd d`() = runTest {
        val s = ListSnapshots(File(dir, ListSnapshots.DIR), UnconfinedTestDispatcher(testScheduler))
        s.of(friends).write(listOf(item()))
        s.of(odd).write(listOf(item()))
        assertSame(s.of(friends), s.of(friends))
        assertEquals(1, s.of(friends).read().size)
        assertTrue(file(friends).exists() && file(odd).exists())
    }

    @Test
    fun `an unpinned list loses its snapshot and a late write does not bring it back`() = runTest {
        // Review Focus: the file names the accounts on a possibly private list
        val s = ListSnapshots(File(dir, ListSnapshots.DIR), UnconfinedTestDispatcher(testScheduler))
        val old = s.of(friends)
        old.write(listOf(item()))
        s.of(odd).write(listOf(item()))
        s.retain(listOf(odd))
        old.write(listOf(item())) // the closed tab's delayed write
        assertFalse(file(friends).exists())
        assertTrue(file(odd).exists())
        assertTrue(s.of(friends).read().isEmpty()) // pinned again: starts empty
    }

    @Test
    fun `logout deletes every list snapshot`() = runTest {
        val s = ListSnapshots(File(dir, ListSnapshots.DIR), UnconfinedTestDispatcher(testScheduler))
        s.of(friends).write(listOf(item()))
        s.deleteAll()
        s.of(friends).write(listOf(item())) // a tab of the old session, still writing
        assertFalse(file(friends).exists())
    }
}
