package com.monostr.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.MediaServer
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.hamcrest.CoreMatchers.anyOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spec 9: a picked picture becomes a tile, is uploaded, and the sent note carries its URL and
 * imeta tag; a refusal is shown and blocks sending; leaving the composer deletes the upload.
 * The media server and the relay both live on 127.0.0.1 inside the test process, the photo
 * picker is answered by Espresso-Intents. Nothing leaves the emulator.
 */
@RunWith(AndroidJUnit4::class)
class MediaUploadTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    private val server = MockWebServer()
    private val stored = ConcurrentHashMap<String, ByteArray>()
    private val deleted = CopyOnWriteArrayList<String>()
    private val authorizations = CopyOnWriteArrayList<String>()
    private val heads = CopyOnWriteArrayList<String>()
    @Volatile private var refuseAs: String? = null
    @Volatile private var claimExisting = false
    private lateinit var base: String
    private lateinit var relay: LoopbackRelay
    private lateinit var picture: File

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "PUT" && path == "/upload" -> {
                        authorizations += request.getHeader("Authorization").orEmpty()
                        val bytes = request.body.readByteArray()
                        refuseAs?.let { return MockResponse().setResponseCode(403).setHeader("X-Monostr-Reason", it).setHeader("X-Reason", "refused") }
                        val hash = sha256(bytes)
                        stored[hash] = bytes
                        MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"url":"$base/$hash.jpg","sha256":"$hash","size":${bytes.size},"type":"image/jpeg","uploaded":1}""")
                    }
                    request.method == "DELETE" -> {
                        deleted += path.trim('/')
                        MockResponse().setResponseCode(204)
                    }
                    // the app asks before it uploads: a picture the server already has is never deleted by the app
                    request.method == "HEAD" -> {
                        val hash = path.trim('/').substringBefore('.')
                        heads += hash
                        if (claimExisting || stored.containsKey(hash)) MockResponse().setResponseCode(200)
                        else MockResponse().setResponseCode(404)
                    }
                    request.method == "GET" -> stored[path.trim('/').substringBefore('.')]
                        ?.let { MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(it)) }
                        ?: MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        relay = LoopbackRelay()
        picture = File(ctx.cacheDir, "upload-test-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        picture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        // the system photo picker, whichever action this Android version uses for it
        Intents.init()
        intending(
            anyOf(
                hasAction(MediaStore.ACTION_PICK_IMAGES),
                hasAction(Intent.ACTION_OPEN_DOCUMENT),
                hasAction("androidx.activity.result.contract.action.PICK_IMAGES"),
                hasAction("com.google.android.gms.provider.action.PICK_IMAGES"),
            ),
        ).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(picture))))
    }

    @After
    fun tearDown() {
        runCatching { Intents.release() } // setUp may have failed before init()
        runBlocking {
            runCatching { entry.uiSettings().setMediaServer(MediaServer.DEFAULT) }
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
        runCatching { server.shutdown() }
        runCatching { relay.close() }
        picture.delete()
    }

    private fun reachComposer() {
        reachFeed()
        rule.onNodeWithTag("fab-compose").performClick()
        rule.waitUntil(10_000) { nodes("compose-attach").isNotEmpty() }
    }

    private fun reachProfileEditor() {
        reachFeed()
        rule.onNodeWithTag("feed-profile").performClick()
        rule.waitUntil(15_000) { nodes("profile-edit").isNotEmpty() }
        rule.onNodeWithTag("profile-edit").performClick()
        // the loopback relay has no profile of the test key: the editor opens empty once it has looked
        rule.waitUntil(30_000) { nodes("edit-picture-pick").isNotEmpty() }
    }

    private fun fieldText(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** The hash of the stored blob [url] names, when it is "<base>/<hash>.jpg" and the server has it. */
    private fun storedHashOf(url: String): String? =
        url.removePrefix("$base/").removeSuffix(".jpg").takeIf { url.startsWith("$base/") && url.endsWith(".jpg") && stored.containsKey(it) }

    private fun longEdgeOf(hash: String): Int {
        val bytes = stored.getValue(hash)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return maxOf(opts.outWidth, opts.outHeight)
    }

    private fun reachFeed() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() || nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("login-secret").isNotEmpty()) {
            rule.onNodeWithTag("login-secret").performTextInput(secret)
            rule.onNodeWithTag("login-secret-button").performClick()
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
        runBlocking {
            entry.session().applyRelays(listOf(relay.url))
            entry.uiSettings().setMediaServer(base)
        }
    }

    private fun attachAndAwaitUpload() {
        rule.onNodeWithTag("compose-attach").performScrollTo().performClick()
        rule.waitUntil(15_000) { nodes("compose-attachment-0").isNotEmpty() }
        rule.waitUntil(30_000) { nodes("compose-attachment-progress-0").isEmpty() }
    }

    @Test
    fun aPickedPictureIsUploadedAndTheNoteCarriesItsUrlAndImeta() {
        reachComposer()
        attachAndAwaitUpload()
        assertTrue(nodes("compose-attachment-error-0").isEmpty())
        assertEquals(1, stored.size)
        val hash = stored.keys.single()
        assertTrue(authorizations.single().startsWith("Nostr "))
        // what was uploaded is the prepared picture, not the file as it was picked
        assertTrue(sha256(picture.readBytes()) != hash)

        rule.onNodeWithTag("compose-sensitive").performScrollTo().performClick()
        rule.onNodeWithTag("compose-text").performTextInput("picture test")
        rule.onNodeWithTag("compose-send").performScrollTo().performClick()
        rule.waitUntil(30_000) { relay.events.any { it.contains("picture test") } }
        val event = relay.events.first { it.contains("picture test") }
        assertTrue(event, event.contains("picture test\\n\\n$base/$hash.jpg"))
        assertTrue(event, event.contains("\"imeta\""))
        assertTrue(event, event.contains("url $base/$hash.jpg"))
        assertTrue(event, event.contains("x $hash"))
        assertTrue(event, event.contains("dim 640x480"))
        assertTrue(event, event.contains("\"content-warning\""))
        // sent: the composer closes, and nothing is deleted at the server
        rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun aRefusedPictureShowsTheReasonAndBlocksSending() {
        refuseAs = "nsfw"
        reachComposer()
        rule.onNodeWithTag("compose-attach").performScrollTo().performClick()
        rule.waitUntil(30_000) { nodes("compose-attachment-error-0").isNotEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("should not go out")
        rule.onNodeWithTag("compose-send").performScrollTo().assertIsNotEnabled()

        // the server relents: "try again" uploads, and the note can be sent
        refuseAs = null
        rule.onNodeWithTag("compose-attachment-retry-0").performScrollTo().performClick()
        rule.waitUntil(30_000) { nodes("compose-attachment-error-0").isEmpty() && nodes("compose-attachment-progress-0").isEmpty() }
        assertEquals(1, stored.size)
        rule.onNodeWithTag("compose-send").performScrollTo().performClick()
        rule.waitUntil(30_000) { relay.events.any { it.contains("should not go out") } }
    }

    @Test
    fun leavingTheComposerDeletesTheUpload() {
        reachComposer()
        attachAndAwaitUpload()
        val hash = stored.keys.single()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        rule.waitUntil(15_000) { deleted.contains(hash) }
        assertTrue(relay.events.none { it.contains(hash) })
    }

    @Test
    fun removingATileDeletesTheUpload() {
        reachComposer()
        attachAndAwaitUpload()
        val hash = stored.keys.single()
        rule.onNodeWithTag("compose-attachment-remove-0").performScrollTo().performClick()
        rule.waitUntil(15_000) { nodes("compose-attachment-0").isEmpty() }
        rule.waitUntil(15_000) { deleted.contains(hash) }
        assertTrue(nodes("compose-sensitive").isEmpty()) // the switch goes with the last picture
    }

    @Test
    fun aPickedProfilePictureAndBannerLandInTheirFields() {
        // larger than both targets, so the avatar (800) and the banner (1500) come out different
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        picture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        reachProfileEditor()

        rule.onNodeWithTag("edit-picture-pick").performScrollTo().performClick()
        rule.waitUntil(30_000) { storedHashOf(fieldText("edit-picture")) != null }
        val avatar = storedHashOf(fieldText("edit-picture"))!!
        assertEquals(800, longEdgeOf(avatar))

        rule.onNodeWithTag("edit-banner-pick").performScrollTo().performClick()
        rule.waitUntil(30_000) { storedHashOf(fieldText("edit-banner")) != null }
        val banner = storedHashOf(fieldText("edit-banner"))!!
        assertEquals(1500, longEdgeOf(banner))
        assertTrue(avatar != banner)
        // the banner went to its own field and left the picture alone
        assertEquals("$base/$avatar.jpg", fieldText("edit-picture"))
        assertTrue(nodes("edit-picture-upload-error").isEmpty() && nodes("edit-banner-upload-error").isEmpty())
    }

    @Test
    fun aRefusedProfilePictureShowsTheReasonUnderItsField() {
        refuseAs = "nsfw"
        reachProfileEditor()
        val before = fieldText("edit-banner")
        rule.onNodeWithTag("edit-banner-pick").performScrollTo().performClick()
        rule.waitUntil(30_000) { nodes("edit-banner-upload-error").isNotEmpty() }
        assertTrue(nodes("edit-picture-upload-error").isEmpty())
        assertEquals(before, fieldText("edit-banner"))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aPictureTheServerAlreadyHadIsNotDeleted() {
        claimExisting = true // every HEAD is answered 200: the picture is not fresh
        reachComposer()
        attachAndAwaitUpload()
        // the upload really happened (the app uploads after a 200 too) and the server was asked first
        assertTrue(nodes("compose-attachment-error-0").isEmpty())
        assertEquals(1, stored.size)
        assertTrue(heads.toString(), heads.contains(stored.keys.single()))
        rule.onNodeWithTag("compose-attachment-remove-0").performScrollTo().performClick()
        rule.waitUntil(15_000) { nodes("compose-attachment-0").isEmpty() }
        // an absent request can only be shown by waiting: give a wrongly sent DELETE time to arrive
        Thread.sleep(1500)
        assertTrue(deleted.toString(), deleted.isEmpty())
    }
}
