package com.monostr.app.dm

import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStore

/** Counts the per-wrap store lookups [DmSync] makes (spec perf2 D1/D4 tests); everything else is the inner store. */
class CountingStore(private val inner: DmStore) : DmStore by inner {
    var messageReads = 0
    /** `hasWrap` + `isDropped` + `pendingAttempts` calls. */
    var lookups = 0

    override suspend fun message(rumorId: String): DmMessage? { messageReads++; return inner.message(rumorId) }
    override suspend fun hasWrap(wrapId: String): Boolean { lookups++; return inner.hasWrap(wrapId) }
    override suspend fun isDropped(wrapId: String): Boolean { lookups++; return inner.isDropped(wrapId) }
    override suspend fun pendingAttempts(wrapId: String): Int { lookups++; return inner.pendingAttempts(wrapId) }
}
