package com.monostr.app.ui.media

import kotlinx.coroutines.async
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LinkPreviewRulesTest {
    private fun note(text: String, media: List<NoteMedia> = emptyList(), quoted: String? = null) =
        Note("d".repeat(64), "a".repeat(64), text, 1000, 1, null, null, null, emptyList(), media = media, displayContent = text, quotedId = quoted)

    @Test
    fun `the first web link wins`() {
        assertEquals("https://a.example/x", LinkPreviewRules.firstPreviewUrl(note("see https://a.example/x and https://b.example/")))
    }

    @Test
    fun `media links, notes with media or a quote, and text without links get none`() {
        assertNull(LinkPreviewRules.firstPreviewUrl(note("pic https://a.example/p.JPG")))
        assertNull(LinkPreviewRules.firstPreviewUrl(note("clip https://a.example/v.mp4?t=3")))
        assertNull(LinkPreviewRules.firstPreviewUrl(note("https://a.example/", media = listOf(NoteMedia("https://a.example/i.png", NoteMedia.Kind.IMAGE)))))
        assertNull(LinkPreviewRules.firstPreviewUrl(note("https://a.example/", quoted = "e".repeat(64))))
        assertNull(LinkPreviewRules.firstPreviewUrl(note("no links, #monero and nostr:npub1p47we20qqrn3rcnrhs22ygt2kayk320fq046y998zscq4hk7tgsqjn2qfl")))
    }

    @Test
    fun `a media link first does not hide a page link after it`() {
        // only the FIRST url counts (spec 2.1): a picture link first means no card
        assertNull(LinkPreviewRules.firstPreviewUrl(note("https://a.example/p.png https://b.example/page")))
    }

    @Test
    fun `the card names the domain of the link a tap opens`() {
        // security review: the page after redirects may sit elsewhere; the card must not show that domain
        assertEquals("kurz.link", LinkPreviewRules.shownDomain("https://www.kurz.link/x?y=1"))
        assertEquals("a.example", LinkPreviewRules.shownDomain("http://a.example:8080/p"))
    }

    @Test
    fun `nothing is fetched before the mute list has loaded, then muted authors stay without`(@org.junit.jupiter.api.io.TempDir dir: java.io.File) = kotlinx.coroutines.runBlocking {
        // review I6: the mute set is empty until the kind-10000 list arrived
        val fetched = java.util.concurrent.atomic.AtomicInteger()
        val previews = com.monostr.app.data.linkpreview.LinkPreviews({ fetched.incrementAndGet(); com.monostr.app.data.linkpreview.LinkPreview(it, "T", null, false) }, dir)
        val muted = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
        val state = kotlinx.coroutines.flow.MutableStateFlow(com.monostr.nostr.repo.ListState())
        val loader = LinkPreviewLoader(previews, muted, state)
        val n = note("https://a.example/")
        val pending = async(kotlinx.coroutines.Dispatchers.Default) { loader.load(n, "https://a.example/") }
        kotlinx.coroutines.delay(100)
        assertEquals(0, fetched.get())
        assertNull(loader.cached(n, "https://a.example/"))
        muted.value = setOf(n.author)
        state.value = com.monostr.nostr.repo.ListState(loaded = true)
        assertNull(pending.await())
        assertEquals(0, fetched.get())
        muted.value = emptySet()
        assertEquals("T", loader.load(n, "https://a.example/")!!.title)
        assertEquals(1, fetched.get())
    }

    @Test
    fun `an author muted while the preview loads gets no card`(@org.junit.jupiter.api.io.TempDir dir: java.io.File) = kotlinx.coroutines.runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val previews = com.monostr.app.data.linkpreview.LinkPreviews({ gate.await(); com.monostr.app.data.linkpreview.LinkPreview(it, "T", null, false) }, dir)
        val muted = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
        val loader = LinkPreviewLoader(previews, muted, kotlinx.coroutines.flow.MutableStateFlow(com.monostr.nostr.repo.ListState(loaded = true)))
        val n = note("https://a.example/")
        val pending = async(kotlinx.coroutines.Dispatchers.Default) { loader.load(n, "https://a.example/") }
        kotlinx.coroutines.delay(100)
        muted.value = setOf(n.author)
        gate.complete(Unit)
        assertNull(pending.await())
        assertNull(loader.cached(n, "https://a.example/"))
    }
}
