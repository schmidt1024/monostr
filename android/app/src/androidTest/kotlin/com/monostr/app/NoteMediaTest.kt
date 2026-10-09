package com.monostr.app

import androidx.compose.material3.MaterialTheme
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

    private fun set(n: Note, settings: MediaSettings = MediaSettings(), onMedia: (String, Int) -> Unit = { _, _ -> }, onVideo: (String) -> Unit = {}) {
        rule.setContent { CompositionLocalProvider(LocalMediaSettings provides settings) { MaterialTheme { NoteCard(ui(n), onOpen = {}, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}, onOpenMedia = onMedia, onOpenVideo = onVideo) } } }
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
        set(note(sensitive = true, onlyMedia = false), settings = MediaSettings(blurSensitive = false))
        rule.onNodeWithTag("note-media-cover-$id8").assertDoesNotExist()
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
}
