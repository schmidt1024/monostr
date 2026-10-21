package com.monostr.app.data.dm

/**
 * Spec perf2 D4: wrap ids this account never decrypts again (stored in messages.wrap_id or remembered in
 * dropped_wraps), in memory, so a replayed wrap is skipped without SQL and without queuing behind a real
 * decrypt. A hit is final; a miss is not (the set may still be loading). Per store, gone at clear and close;
 * wrap ids are public relay event ids, no message content.
 */
class KnownWraps {
    private val ids = HashSet<String>()
    private var loading = false
    private var loaded = false
    private var generation = 0

    @Synchronized operator fun contains(id: String): Boolean = id in ids

    @Synchronized fun add(id: String?) {
        if (id != null) ids += id
    }

    /** A token when the caller should load the set now; null while it loads or once it is loaded. */
    @Synchronized fun startLoad(): Int? {
        if (loading || loaded) return null
        loading = true
        return generation
    }

    @Synchronized fun loaded(token: Int, all: Collection<String>) {
        if (token != generation) return // a clear came after the load started
        ids += all
        loading = false
        loaded = true
    }

    @Synchronized fun failed(token: Int) {
        if (token == generation) loading = false
    }

    @Synchronized fun clear() {
        ids.clear()
        generation++
        loading = false
        loaded = false
    }
}
