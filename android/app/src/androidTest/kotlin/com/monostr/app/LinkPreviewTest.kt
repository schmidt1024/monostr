package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.linkpreview.AddressPolicy
import com.monostr.app.data.linkpreview.LinkPreviewFetcher
import com.monostr.app.data.linkpreview.LinkPreviews
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.media.LinkPreviewLoader
import com.monostr.app.ui.media.LocalLinkPreviewLoader
import com.monostr.app.ui.media.LocalMediaSettings
import com.monostr.app.ui.media.MediaReveals
import com.monostr.app.ui.media.MediaSettings
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Spec link previews §4: the switch decides whether the phone contacts linked sites at all. */
@RunWith(AndroidJUnit4::class)
class LinkPreviewTest {
    @get:Rule val rule = createComposeRule()
    private val id = "c".repeat(64)
    private val id8 = id.take(8)
    private val author = "a".repeat(64)
    private lateinit var server: MockWebServer
    private lateinit var cache: File
    private val opened = mutableListOf<String>()

    @Before fun start() {
        MediaReveals.resetForTests()
        server = MockWebServer().apply { start() }
        cache = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lp-test-${System.nanoTime()}")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""<head><meta property="og:title" content="Monero news"></head>"""))
    }

    @After fun stop() { server.shutdown(); cache.deleteRecursively() }

    private fun note(media: Boolean = false): Note {
        val link = server.url("/story").toString()
        return Note(id, author, "read $link", 1000, 1, null, null, null, emptyList(),
            media = if (media) listOf(NoteMedia("https://192.0.2.1/a.jpg", NoteMedia.Kind.IMAGE)) else emptyList(), displayContent = "read $link")
    }

    private fun set(n: Note, on: Boolean, muted: Set<String> = emptySet()) {
        val previews = LinkPreviews(LinkPreviewFetcher(OkHttpClient(), "monostr/test", AddressPolicy.ANY)::fetch, cache)
        val loader = LinkPreviewLoader(previews, MutableStateFlow(muted), MutableStateFlow(com.monostr.nostr.repo.ListState(loaded = true)))
        val uri = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        rule.setContent {
            CompositionLocalProvider(
                LocalMediaSettings provides MediaSettings(linkPreviews = on), LocalLinkPreviewLoader provides loader, LocalUriHandler provides uri,
            ) { MaterialTheme { NoteCard(NoteUi(n, Profile.empty(author), Profile.empty(author)), onOpen = {}, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}) } }
        }
    }

    private fun waitForCard() = rule.waitUntil(5_000) { rule.onAllNodesWithTag("link-preview-$id8").fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun setting_off_makes_no_request_and_shows_no_card() {
        set(note(), on = false)
        rule.waitForIdle()
        Thread.sleep(500)
        rule.onNodeWithTag("link-preview-$id8").assertDoesNotExist()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun setting_on_shows_title_and_domain_and_a_tap_opens_the_link() {
        set(note(), on = true)
        waitForCard()
        rule.onNodeWithText("Monero news").assertIsDisplayed()
        rule.onNodeWithText(server.hostName).assertIsDisplayed()
        rule.onNodeWithText("Monero news").performClick()
        assertEquals(listOf(server.url("/story").toString()), opened)
    }

    @Test
    fun note_with_a_picture_gets_no_card() {
        set(note(media = true), on = true)
        rule.waitForIdle()
        Thread.sleep(500)
        rule.onNodeWithTag("link-preview-$id8").assertDoesNotExist()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun muted_author_gets_no_card() {
        set(note(), on = true, muted = setOf(author))
        rule.waitForIdle()
        Thread.sleep(500)
        rule.onNodeWithTag("link-preview-$id8").assertDoesNotExist()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun the_card_names_the_domain_a_tap_opens_even_after_a_redirect_elsewhere() {
        // security review: the note links localhost, the page answers from 127.0.0.1; the card must say localhost
        server.shutdown()
        server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/final"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""<head><meta property="og:title" content="Elsewhere"></head>"""))
        val link = "http://localhost:${server.port}/story"
        val n = Note(id, author, "read $link", 1000, 1, null, null, null, emptyList(), displayContent = "read $link")
        set(n, on = true)
        waitForCard()
        rule.onNodeWithText("Elsewhere").assertIsDisplayed()
        rule.onNodeWithText("localhost").assertIsDisplayed()
        rule.onNodeWithText("127.0.0.1").assertDoesNotExist()
    }
}
