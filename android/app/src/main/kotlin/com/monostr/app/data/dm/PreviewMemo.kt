package com.monostr.app.data.dm

import java.util.concurrent.ConcurrentHashMap

/**
 * Spec perf2 D3: the list preview per peer, parsed again (NoteMedia.parse) only when the peer's last
 * message changed. Memory only, per store; holds the same decrypted text the list shows; cleared at
 * clear and close.
 */
class PreviewMemo(private val preview: (String) -> String = DmText::preview) {
    private class Entry(val lastAt: Long, val content: String, val text: String)

    private val byPeer = ConcurrentHashMap<String, Entry>()

    fun of(peer: String, lastAt: Long, content: String): String {
        byPeer[peer]?.let { if (it.lastAt == lastAt && it.content == content) return it.text }
        return preview(content).also { byPeer[peer] = Entry(lastAt, content, it) }
    }

    fun clear() = byPeer.clear()
}
