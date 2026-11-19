package com.monostr.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.monostr.app.ui.media.NoteMediaRow
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import com.monostr.app.ui.media.MediaRatios
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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

    // backlog 93: 2-20 items as one horizontal row; notes built like note(...), the row width from a fixed-width Box
    private fun gallery(vararg media: NoteMedia, sensitive: Boolean = false) = Note(
        id, "a".repeat(64), "", 1000, 1, null, null, null, emptyList(), media = media.toList(),
        contentWarning = if (sensitive) "" else null, displayContent = "",
    )
    private fun pic(i: Int, dim: Pair<Int, Int>? = 300 to 400) =
        NoteMedia("https://192.0.2.1/g$i-${System.nanoTime()}.jpg", NoteMedia.Kind.IMAGE, dim = dim)

    private fun setRow(n: Note, widthDp: Int, onMedia: (Int) -> Unit = {}, onVideo: (String) -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalMediaSettings provides MediaSettings(mediaOnTap = false)) {
                MaterialTheme { Column { Box(Modifier.width(widthDp.dp)) { NoteMediaRow(n, onOpenMedia = onMedia, onOpenVideo = onVideo) } } }
            }
        }
    }

    private fun widthOf(tag: String): Float = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let { (it.right - it.left).value }

    @Test
    fun gallery_of_seven_scrolls_and_shows_dots() {
        setRow(gallery(*Array(7) { pic(it) }), 360)
        rule.onNodeWithTag("note-media-row-$id8").assertIsDisplayed()
        rule.onNodeWithTag("note-media-dots-$id8").assertIsDisplayed()
        rule.onNodeWithTag("note-media-$id8-0").assertIsDisplayed()
        rule.onNodeWithTag("note-media-$id8-6").assertDoesNotExist()
        rule.onNodeWithTag("note-media-row-$id8").performScrollToIndex(6)
        rule.onNodeWithTag("note-media-$id8-6").assertIsDisplayed()
    }

    @Test
    fun two_that_fit_have_no_dots() {
        setRow(gallery(pic(0, 400 to 400), pic(1, 200 to 400)), 360)
        rule.onNodeWithTag("note-media-dots-$id8").assertDoesNotExist()
        rule.onNodeWithTag("note-media-$id8-0").assertIsDisplayed()
        rule.onNodeWithTag("note-media-$id8-1").assertIsDisplayed()
    }

    @Test
    fun tap_on_fifth_card_opens_the_viewer_at_four() {
        var opened: Pair<String, Int>? = null
        set(gallery(*Array(7) { pic(it) }), onMedia = { nid, i -> opened = nid to i })
        rule.onNodeWithTag("note-media-row-$id8").performScrollToIndex(4)
        rule.onNodeWithTag("note-media-$id8-4").performClick()
        assertEquals(id to 4, opened)
    }

    @Test
    fun video_card_in_a_row_opens_the_player() {
        // a wide first picture keeps the row low enough that a 16:9 video card fits under the 0.85 x width clamp
        val url = "https://v.example/g-${System.nanoTime()}.mp4"
        var opened = ""
        setRow(gallery(pic(0, 1600 to 900), NoteMedia(url, NoteMedia.Kind.VIDEO)), 360, onVideo = { opened = it })
        // unclipped size: the video card runs past the row's right edge
        val size = rule.onNodeWithTag("note-media-$id8-1").fetchSemanticsNode().size
        assertEquals(16f / 9f, size.width.toFloat() / size.height, 0.05f)
        rule.onNodeWithTag("note-media-$id8-1").performClick()
        assertEquals(url, opened)
    }

    @Test
    fun covered_gallery_has_no_dots_and_one_cover() {
        setRow(gallery(*Array(7) { pic(it) }, sensitive = true), 360)
        rule.onNodeWithTag("note-media-cover-$id8").assertIsDisplayed()
        rule.onNodeWithTag("note-media-dots-$id8").assertDoesNotExist()
    }

    @Test
    fun row_uses_its_own_width_inside_a_narrow_parent() {
        setRow(gallery(pic(0), pic(1), pic(2)), 250)
        rule.onNodeWithTag("note-media-row-$id8").assertWidthIsEqualTo(250.dp)
        assertEquals(250f * 0.62f, widthOf("note-media-$id8-0"), 1.5f)
    }

    @Test
    fun video_shows_its_length_from_imeta_duration() {
        set(single(NoteMedia("https://v.example/d-${System.nanoTime()}.mp4", NoteMedia.Kind.VIDEO, durationSeconds = 492)))
        rule.onNodeWithTag("note-media-duration-$id8-0", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("8:12")
    }

    @Test
    fun video_without_duration_has_no_length_badge() {
        set(single(NoteMedia("https://v.example/e-${System.nanoTime()}.mp4", NoteMedia.Kind.VIDEO)))
        rule.onNodeWithTag("note-media-duration-$id8-0", useUnmergedTree = true).assertDoesNotExist()
    }
}
