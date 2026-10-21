package com.monostr.nostr

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SyncMarksTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `a mark is kept across instances, a broken file reads as none`() = runTest {
        val file = dir.resolve("nostr-x").resolve(SyncMarks.FILE_NAME).toFile()
        val marks = SyncMarks(file)
        assertNull(marks.get("own-reactions:a"))
        marks.put("own-reactions:a", 1_000)
        marks.put("own-reactions:b", 2_000)
        assertEquals(1_000L, SyncMarks(file).get("own-reactions:a"))
        assertEquals(2_000L, SyncMarks(file).get("own-reactions:b"))
        file.writeText("not json")
        assertNull(SyncMarks(file).get("own-reactions:a"))
    }

    @Test
    fun `delete removes the file and a later write cannot bring it back`() = runTest {
        val file = dir.resolve(SyncMarks.FILE_NAME).toFile()
        val marks = SyncMarks(file)
        marks.put("k", 1)
        marks.delete()
        assertFalse(file.exists())
        marks.put("k", 2)
        assertFalse(file.exists())
        assertNull(SyncMarks(file).get("k"))
    }
}
