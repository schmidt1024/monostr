package com.monostr.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.media.PictureSaver
import com.monostr.app.media.PictureSharer
import com.monostr.app.ui.media.MediaSheetItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Spec 2026-10-16 §7: save into Pictures/monostr, share through the FileProvider, Save/Share first in the sheet. */
@RunWith(AndroidJUnit4::class)
class PictureExportTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A small JPEG in a temp file, as PictureSource leaves it under cacheDir/export. */
    private fun jpeg(): File {
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        return File.createTempFile("picture", ".tmp", dir).apply { writeBytes(bytes); deleteOnExit() }
    }

    @Test
    fun aSavedPictureLandsInPicturesMonostr() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val name = "monostr-test${System.nanoTime()}.jpg"
        val uri = PictureSaver.save(ctx, jpeg(), name, "image/jpeg")
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Pictures/monostr/", c.getString(0))
                assertEquals(name, c.getString(1))
                assertEquals(0, c.getInt(2))
            }
            assertTrue(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.isNotEmpty())
        } finally {
            ctx.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun aSharedPictureIsReadableThroughTheProviderAndTheOldOneGoes() {
        val first = PictureSharer.prepare(ctx, jpeg(), "monostr-first.jpg")
        assertEquals("content", first.scheme)
        assertEquals("${ctx.packageName}.files", first.authority)
        assertTrue(ctx.contentResolver.openInputStream(first)!!.use { it.readBytes() }.isNotEmpty())
        PictureSharer.prepare(ctx, jpeg(), "monostr-second.jpg")
        assertEquals(listOf("monostr-second.jpg"), File(ctx.cacheDir, PictureSharer.DIR).list()!!.toList())
    }

    @Test
    fun theSheetOffersSaveAndShareFirst() {
        var saved = 0
        var shared = 0
        rule.setContent { MaterialTheme { Column { MediaSheetItems(onSave = { saved++ }, onShare = { shared++ }, onCopyLink = {}, onOpenBrowser = {}) } } }
        rule.onNodeWithText("Save picture").assertIsDisplayed()
        rule.onNodeWithText("Share picture").assertIsDisplayed()
        val save = rule.onNodeWithTag("media-save").getBoundsInRoot().top
        val share = rule.onNodeWithTag("media-share").getBoundsInRoot().top
        val copy = rule.onNodeWithTag("media-copy-link").getBoundsInRoot().top
        assertTrue(save < share && share < copy)
        rule.onNodeWithTag("media-save").performClick()
        rule.onNodeWithTag("media-share").performClick()
        rule.runOnIdle {
            assertEquals(1, saved)
            assertEquals(1, shared)
        }
    }
}
