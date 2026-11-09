package com.monostr.app.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwitchLockTest {
    @Test
    fun `finishing after a take runs the step and unlocks`() = runTest {
        val mutex = Mutex()
        val lock = SwitchLock(mutex)
        lock.take()
        assertTrue(mutex.isLocked)
        var ran = false
        lock.finish { ran = true; assertTrue(mutex.isLocked) }
        assertTrue(ran)
        assertFalse(mutex.isLocked)
    }

    @Test
    fun `a stop that threw before the lock, finishing neither throws nor runs anything`() = runTest {
        val lock = SwitchLock(Mutex())
        var ran = false
        lock.finish { ran = true }
        assertFalse(ran)
    }

    @Test
    fun `finishing without a take leaves someone else's lock alone`() = runTest {
        val mutex = Mutex()
        mutex.lock()
        SwitchLock(mutex).finish { error("runs under a lock it does not hold") }
        assertTrue(mutex.isLocked)
    }

    @Test
    fun `the step's own failure comes out and the lock is free`() = runTest {
        val mutex = Mutex()
        val lock = SwitchLock(mutex)
        lock.take()
        val thrown = runCatching { lock.finish { throw IllegalArgumentException("the first error") } }.exceptionOrNull()
        assertEquals("the first error", thrown?.message)
        assertFalse(mutex.isLocked)
    }

    @Test
    fun `the lock is taken again after a finish, and a second finish does nothing`() = runTest {
        val mutex = Mutex()
        val lock = SwitchLock(mutex)
        repeat(2) {
            lock.take()
            lock.finish {}
            lock.finish { error("not held any more") }
            assertFalse(mutex.isLocked)
        }
    }
}
