package com.monostr.app

import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.compose.AsyncImage
import com.monostr.app.data.GifMode
import com.monostr.app.ui.media.GifImage
import com.monostr.app.ui.media.LocalMediaSettings
import com.monostr.app.ui.media.MediaSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Backlog 66, checked on the drawn pixels: two frames of 500 ms, red and blue. */
@RunWith(AndroidJUnit4::class)
class GifPlaybackDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val gif = Base64.decode("R0lGODlhIAAgAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQIMgAAACwAAAAAIAAgAAAINQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJUmRAACH5BAgyAAAALAAAAAAgACAAgQAA/wAAAAAAAAAAAAg1AAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmTKFOqXMlSZEAAOw==", Base64.DEFAULT)
    private val ts = System.currentTimeMillis()
    private fun url(name: String) = server.url("/$name-$ts.gif").toString()
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @Before fun start() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setHeader("Content-Type", "image/gif").setBody(Buffer().write(gif))
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    /** The distinct colours the node shows over [ms]; the test clock runs freely so the animation goes on. */
    private fun colours(tag: String, ms: Long): Set<Int> {
        val seen = HashSet<Int>()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val b = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            seen += b.getPixel(b.width / 2, b.height / 2)
            Thread.sleep(150)
        }
        return seen
    }

    private fun gifCell(mode: GifMode, key: String, u: String) {
        rule.setContent {
            CompositionLocalProvider(LocalMediaSettings provides MediaSettings(gifMode = mode)) {
                Box(Modifier.size(100.dp).testTag("cell")) {
                    GifImage(u, key = key, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center, placeholder = null, onSize = null, onError = {})
                }
            }
        }
    }

    @Test
    fun aGifPlaysAboutFiveSecondsThenStaysAndTheBadgePlaysItAgain() {
        val key = "auto-$ts"
        gifCell(GifMode.AUTO, key, url("auto"))
        rule.waitUntil(15_000) { nodes("gif-pause-$key").isNotEmpty() }
        assertTrue("it moves while it plays", colours("cell", 1_500).size >= 2)
        rule.waitUntil(10_000) { nodes("gif-play-$key").isNotEmpty() }
        assertEquals("it stays on a frame after that", 1, colours("cell", 1_500).size)
        rule.onNodeWithTag("gif-play-$key", useUnmergedTree = true).performClick()
        rule.waitUntil(3_000) { nodes("gif-pause-$key").isNotEmpty() }
        assertTrue("the badge plays it again", colours("cell", 1_500).size >= 2)
        rule.onNodeWithTag("gif-pause-$key", useUnmergedTree = true).performClick()
        rule.waitUntil(3_000) { nodes("gif-play-$key").isNotEmpty() }
        assertEquals("and pauses it", 1, colours("cell", 1_200).size)
    }

    @Test
    fun onTapWaitsForTheBadge() {
        val key = "tap-$ts"
        gifCell(GifMode.ON_TAP, key, url("tap"))
        rule.waitUntil(15_000) { nodes("gif-play-$key").isNotEmpty() }
        assertEquals("it does not start by itself", 1, colours("cell", 1_500).size)
        rule.onNodeWithTag("gif-play-$key", useUnmergedTree = true).performClick()
        rule.waitUntil(3_000) { nodes("gif-pause-$key").isNotEmpty() }
        assertTrue(colours("cell", 1_500).size >= 2)
    }

    @Test
    fun anOrdinaryImageNeverAnimatesAGif() {
        val u = url("plain")
        rule.setContent { AsyncImage(u, contentDescription = null, modifier = Modifier.size(100.dp).testTag("plain")) }
        rule.waitUntil(15_000) { val b = rule.onNodeWithTag("plain").captureToImage().asAndroidBitmap(); b.getPixel(b.width / 2, b.height / 2) != 0 }
        assertEquals("avatars, thumbnails and quote cards stay still", 1, colours("plain", 1_500).size)
    }

    @Test
    fun offShowsTheFirstFrameWithoutABadge() {
        val key = "off-$ts"
        gifCell(GifMode.OFF, key, url("off"))
        rule.waitUntil(15_000) { val b = rule.onNodeWithTag("cell").captureToImage().asAndroidBitmap(); b.getPixel(b.width / 2, b.height / 2) != 0 }
        assertEquals(1, colours("cell", 1_500).size)
        assertTrue(nodes("gif-play-$key").isEmpty() && nodes("gif-pause-$key").isEmpty())
    }
}
