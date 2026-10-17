package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import com.monostr.app.ui.media.MediaRatios
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.media.LocalMediaSettings
import com.monostr.app.ui.media.MediaSettings
import com.monostr.app.ui.media.MediaReveals
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteMediaTest {
    @get:Rule val rule = createComposeRule()
    private val id = "d".repeat(64)
    private val id8 = id.take(8)

    // MediaReveals is a process-wide singleton (spec 3.2) and every case here reuses the same note
    // id, so a reveal from one test would otherwise leak into the next.
    @Before fun resetReveals() { MediaReveals.resetForTests() }
    // The picture uses a TEST-NET-1 literal (RFC 5737, 192.0.2.0/24): unlike the "x.example" domain
    // (a reserved TLD that fails DNS resolution near-instantly), a literal, non-routable IP makes
    // the connection attempt hang until Coil's connect timeout, so it is still "loading" -- not yet
    // "failed" -- for the tap-opens-the-viewer assertions below, matching real "picture still
    // loading" behaviour rather than racing a network error against the click.
    private fun note(sensitive: Boolean, onlyMedia: Boolean) = Note(
        id, "a".repeat(64), "https://192.0.2.1/a.jpg", 1000, 1, null, null, null, emptyList(),
        media = listOf(NoteMedia("https://192.0.2.1/a.jpg", NoteMedia.Kind.IMAGE), NoteMedia("https://v.example/c.mp4", NoteMedia.Kind.VIDEO)),
        contentWarning = if (sensitive) "" else null, displayContent = if (onlyMedia) "" else "text",
    )
    private fun ui(n: Note) = NoteUi(n, Profile.empty(n.author), Profile.empty(n.author))

    private fun set(n: Note, settings: MediaSettings = MediaSettings(mediaOnTap = false), onMedia: (String, Int) -> Unit = { _, _ -> }, onVideo: (String) -> Unit = {}, dark: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalMediaSettings provides settings) {
                val scheme = if (dark) darkColorScheme() else MaterialTheme.colorScheme
                MaterialTheme(colorScheme = scheme) { NoteCard(ui(n), onOpen = {}, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}, onOpenMedia = onMedia, onOpenVideo = onVideo) }
            }
        }
    }

    /** Share of near-white pixels: the cover's icon and text must stand out whatever the theme. */
    private fun brightShare(tag: String): Float {
        val px = rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        var bright = 0
        for (x in 0 until px.width) for (y in 0 until px.height) {
            val c = px[x, y]
            if (c.red > 0.85f && c.green > 0.85f && c.blue > 0.85f) bright++
        }
        return bright.toFloat() / (px.width * px.height)
    }

    @Test
    fun media_row_shows_picture_and_video_placeholder_and_hides_empty_text() {
        set(note(sensitive = false, onlyMedia = true))
        rule.onNodeWithTag("note-media-$id8-0").assertIsDisplayed()
        rule.onNodeWithTag("note-media-$id8-1").assertIsDisplayed()
        rule.onNodeWithTag("note-text-$id8").assertDoesNotExist()
    }

    @Test
    fun sensitive_note_is_covered_until_tapped() {
        var opened = -1
        set(note(sensitive = true, onlyMedia = false), onMedia = { _, i -> opened = i })
        rule.onNodeWithTag("note-media-cover-$id8").assertIsDisplayed()
        rule.onNodeWithTag("note-media-cover-$id8").performClick()
        rule.onNodeWithTag("note-media-cover-$id8").assertDoesNotExist()
        rule.onNodeWithTag("note-media-$id8-0").performClick()
        assertEquals(0, opened)
    }

    @Test
    fun blur_setting_off_shows_sensitive_media_directly() {
        set(note(sensitive = true, onlyMedia = false), settings = MediaSettings(blurSensitive = false, mediaOnTap = false))
        rule.onNodeWithTag("note-media-cover-$id8").assertDoesNotExist()
    }

    @Test
    fun load_cover_is_readable_in_the_dark_theme_without_blurhash() {
        set(note(sensitive = false, onlyMedia = false), settings = MediaSettings(mediaOnTap = true), dark = true)
        rule.onNodeWithTag("media-cover-badge-$id8", useUnmergedTree = true).assertIsDisplayed()
        val share = brightShare("media-cover-badge-$id8")
        assertTrue("icon and text must be light on the badge, bright share $share", share > 0.03f)
    }

    @Test
    fun sensitive_cover_is_readable_in_the_dark_theme() {
        set(note(sensitive = true, onlyMedia = false), dark = true)
        val share = brightShare("media-cover-badge-$id8")
        assertTrue("bright share $share", share > 0.03f)
    }

    @Test
    fun media_on_tap_shows_a_load_cover() {
        set(note(sensitive = false, onlyMedia = false), settings = MediaSettings(mediaOnTap = true))
        rule.onNodeWithTag("note-media-load-$id8").assertIsDisplayed()
        rule.onNodeWithTag("note-media-load-$id8").performClick()
        rule.onNodeWithTag("note-media-load-$id8").assertDoesNotExist()
    }

    @Test
    fun video_placeholder_opens_the_player() {
        var url = ""
        set(note(sensitive = false, onlyMedia = false), onVideo = { url = it })
        rule.onNodeWithTag("note-media-$id8-1").performClick()
        assertEquals("https://v.example/c.mp4", url)
    }

    private fun single(media: NoteMedia) = Note(
        id, "a".repeat(64), media.url, 1000, 1, null, null, null, emptyList(), media = listOf(media), displayContent = "",
    )

    @Test
    fun single_picture_without_dim_takes_its_remembered_ratio() {
        val url = "https://192.0.2.1/wide-${System.nanoTime()}.jpg"
        MediaRatios.put(url, 1600, 800)
        set(single(NoteMedia(url, NoteMedia.Kind.IMAGE)))
        val b = rule.onNodeWithTag("note-media-$id8-0").getBoundsInRoot()
        assertEquals(2f, (b.right - b.left) / (b.bottom - b.top), 0.05f)
    }

    @Test
    fun single_video_without_dim_and_poster_is_16_to_9() {
        set(single(NoteMedia("https://v.example/c-${System.nanoTime()}.mp4", NoteMedia.Kind.VIDEO)))
        val b = rule.onNodeWithTag("note-media-$id8-0").getBoundsInRoot()
        assertEquals(16f / 9f, (b.right - b.left) / (b.bottom - b.top), 0.05f)
    }

    @Test
    fun single_picture_without_dim_and_unknown_size_keeps_the_4_to_3_box() {
        set(single(NoteMedia("https://192.0.2.1/unknown-${System.nanoTime()}.jpg", NoteMedia.Kind.IMAGE)))
        val b = rule.onNodeWithTag("note-media-$id8-0").getBoundsInRoot()
        assertEquals(4f / 3f, (b.right - b.left) / (b.bottom - b.top), 0.05f)
    }
}
