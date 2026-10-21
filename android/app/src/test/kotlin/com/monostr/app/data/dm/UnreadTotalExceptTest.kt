package com.monostr.app.data.dm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnreadTotalExceptTest {
    /** A store whose conversations query throws, as a closing database does during logout. */
    private val failing = object : DmStore by InMemoryDmStore() {
        override fun conversations(): Flow<List<Conversation>> = flow { throw IllegalStateException("database closed") }
    }

    @Test
    fun `a failing conversations query shows no badge instead of crashing`() = runTest {
        assertEquals(listOf(0), failing.unreadTotalExcept(MutableStateFlow(emptySet())).toList())
    }
}
