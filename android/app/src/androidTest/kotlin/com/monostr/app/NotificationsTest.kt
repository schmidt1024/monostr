package com.monostr.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Nip19Event
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

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
        rule.onNodeWithTag("login-secret-button").performSemanticsAction(SemanticsActions.OnClick)
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

    private fun quoteOf(mine: Event, from: Keys, text: String): Event {
        val uri = Nip19Event(mine.id(), me.publicKey(), Kind(1u), emptyList()).toNostrUri()
        return EventBuilder.textNote("$text\n\n$uri").tags(listOf(Tag.parse(listOf("q", mine.id().toHex(), "", me.publicKey().toHex())), Tag.parse(listOf("p", me.publicKey().toHex())))).signWithKeys(from)
    }

    @Test
    fun quoteRow_showsCard_cardOpensOwnNote() {
        reachFeed()
        val alice = Keys.generate()
        follow(alice, """{"name":"alice-q"}""")
        val mine = EventBuilder.textNote("quoted original ${System.currentTimeMillis()}").signWithKeys(me)
        val quote = quoteOf(mine, alice, "my take")
        seed(mine, quote)
        openNotifications()
        rule.waitUntil(15_000) { nodes("notification-${quote.id().toHex()}").isNotEmpty() }
        rule.onNodeWithText("my take").assertIsDisplayed()
        assertEquals(0, nodes("notification-about-${quote.id().toHex()}").size)
        rule.waitUntil(15_000) { nodes("quote-${id8(mine)}").isNotEmpty() }
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("quoted original", substring = true)).fetchSemanticsNodes().isNotEmpty() } // loaded, so the tap has a target
        // the card's centre can land on the author row (profile); the bottom padding only the card handles
        rule.onNodeWithTag("quote-${id8(mine)}").performTouchInput { click(Offset(centerX, height - 4.dp.toPx())) }
        rule.waitUntil(15_000) { nodes("note-text-${id8(mine)}").isNotEmpty() }
    }

    @Test
    fun quoteRow_missingQuotedNote_noCard() {
        reachFeed()
        val alice = Keys.generate()
        follow(alice, """{"name":"alice-m"}""")
        val unseen = EventBuilder.textNote("never stored ${System.currentTimeMillis()}").signWithKeys(me)
        val quote = quoteOf(unseen, alice, "quoting the unknown")
        seed(quote)
        openNotifications()
        rule.waitUntil(15_000) { nodes("notification-${quote.id().toHex()}").isNotEmpty() }
        rule.onNodeWithText("quoting the unknown").assertIsDisplayed()
        Thread.sleep(2_000)
        assertEquals(0, nodes("quote-${id8(unseen)}").size)
    }
}
