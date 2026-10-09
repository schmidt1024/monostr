package com.monostr.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Timestamp

/** Spec 11-C: notifications show the note they are about; likes and reposts of one note share a row. */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    companion object {
        // the notifications tab asks for POST_NOTIFICATIONS on Android 13+; granted up front so no system dialog covers the app
        @JvmStatic
        @BeforeClass
        fun grantNotifications() {
            if (Build.VERSION.SDK_INT >= 33) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    @After fun logout() { runBlocking { runCatching { entry.session().logout() } } }

    private fun reachFeed() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        rule.onNodeWithTag("login-secret").performTextInput(secret)
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    private fun follow(alice: Keys, metadata: String?) {
        val ready = entry.session().requireReady()
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            if (metadata != null) ready.engine.save(EventBuilder.metadata(Metadata.fromJson(metadata)).signWithKeys(alice))
        }
    }

    private fun seed(vararg events: Event) {
        val ready = entry.session().requireReady()
        runBlocking { events.forEach { ready.engine.save(it) } }
    }

    private fun openNotifications() {
        rule.onNodeWithTag("tab-notifications").performClick()
        rule.waitUntil(30_000) { nodes("notifications-list").isNotEmpty() }
    }

    private fun id8(e: Event) = e.id().toHex().take(8)

    @Test
    fun twoLikesOfOneNoteAreOneRowWithBothNamesAndTheExcerpt() {
        reachFeed()
        val alice = Keys.generate(); val bob = Keys.generate()
        follow(alice, """{"name":"alice-n"}""")
        seed(EventBuilder.metadata(Metadata.fromJson("""{"name":"bob-n"}""")).signWithKeys(bob))
        val mine = EventBuilder.textNote("my own note ${System.currentTimeMillis()}").signWithKeys(me)
        val now = System.currentTimeMillis() / 1000
        seed(mine, EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs((now - 20).toULong())).signWithKeys(alice), EventBuilder.reaction(mine, "+").customCreatedAt(Timestamp.fromSecs((now - 10).toULong())).signWithKeys(bob))
        openNotifications()
        val key = "REACTION:${mine.id().toHex()}"
        rule.waitUntil(15_000) { nodes("notification-$key").isNotEmpty() }
        rule.onNodeWithTag("notification-$key").assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.notif_liked_two, "bob-n", "alice-n")).assertIsDisplayed() // newest actor first
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("notification-about-$key"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("notification-about-$key", useUnmergedTree = true).assertTextContains("my own note", substring = true)
        assertEquals(1, nodes("notification-avatar-${bob.publicKey().toHex().take(8)}").size)
        rule.onNodeWithTag("notification-avatar-${bob.publicKey().toHex().take(8)}").assertContentDescriptionEquals(ctx.getString(R.string.avatar_of, "bob-n"))
        rule.onNodeWithTag("notification-$key").performClick()
        rule.waitUntil(15_000) { nodes("note-time-${id8(mine)}").isNotEmpty() } // the thread of my note, focused
    }

    @Test
    fun aReplyShowsWhatItAnswersAndOpensItsOwnThread() {
        reachFeed()
        val alice = Keys.generate()
        follow(alice, """{"name":"alice-r"}""")
        val mine = EventBuilder.textNote("question ${System.currentTimeMillis()}").signWithKeys(me)
        val reply = EventBuilder.textNoteReply("the answer", mine, null, null).signWithKeys(alice)
        seed(mine, reply)
        openNotifications()
        rule.waitUntil(15_000) { nodes("notification-${reply.id().toHex()}").isNotEmpty() }
        rule.onNodeWithText("the answer").assertIsDisplayed()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("notification-about-${reply.id().toHex()}"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("notification-about-${reply.id().toHex()}", useUnmergedTree = true).assertTextContains("question", substring = true)
        rule.onNodeWithTag("notification-${reply.id().toHex()}").performClick()
        rule.waitUntil(15_000) { nodes("note-time-${id8(reply)}").isNotEmpty() }
    }

    @Test
    fun aRepostIsARow() {
        reachFeed()
        val alice = Keys.generate()
        follow(alice, """{"name":"alice-p"}""")
        val mine = EventBuilder.textNote("reposted ${System.currentTimeMillis()}").signWithKeys(me)
        seed(mine, EventBuilder.repost(mine, null).signWithKeys(alice))
        openNotifications()
        val key = "REPOST:${mine.id().toHex()}"
        rule.waitUntil(15_000) { nodes("notification-$key").isNotEmpty() }
        rule.onNodeWithText(ctx.getString(R.string.notif_reposted_one, "alice-p")).assertIsDisplayed()
    }
}
