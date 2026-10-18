package com.monostr.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Tag

/** Spec 2026-10-18 §6: create a list from the profile menu, pin it, read it as a tab, unpin; pin a foreign list. Needs network. */
@RunWith(AndroidJUnit4::class)
class ListsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    /** A fresh key per run: the lists and tabs this test publishes never land on a shared account. */
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun textShown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @After fun logout() { runBlocking { runCatching { entry.session().logout() } } }

    private fun reachFeed() {
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
    }

    /** Logged in as a fresh account following [names] (one key each), each with one note on screen. */
    private fun seed(vararg names: String): List<Pair<Keys, Event>> {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        reachFeed()
        val ready = entry.session().requireReady()
        val people = names.map { name ->
            val keys = Keys.generate()
            runBlocking { ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"$name"}""")).signWithKeys(keys)) }
            keys to EventBuilder.textNote("$name says hi ${System.currentTimeMillis()}").signWithKeys(keys)
        }
        runBlocking {
            ready.engine.save(EventBuilder.contactList(people.map { Contact(it.first.publicKey(), null, null) }).signWithKeys(me))
            people.forEach { ready.engine.save(it.second) }
        }
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
        people.forEach { (_, note) -> rule.waitUntil(30_000) { textShown(note.content()) } }
        return people
    }

    /** Opens the author's profile through the avatar of [note] in the feed. */
    private fun openProfileOf(note: Event) {
        rule.onNodeWithTag("note-avatar-${note.id().toHex().take(8)}").performClick()
        rule.waitUntil(15_000) { nodes("profile-tab-lists").isNotEmpty() }
    }

    @Test
    fun createAListFromTheProfileMenuPinItReadItAndUnpinIt() {
        val (alice, aliceNote) = seed("alice-lists").single()
        val ready = entry.session().requireReady()
        openProfileOf(aliceNote)
        rule.onNodeWithTag("profile-menu").performClick()
        rule.onNodeWithTag("profile-menu-lists").performClick()
        rule.waitUntil(30_000) { nodes("add-to-list-create").isNotEmpty() }
        rule.onNodeWithTag("add-to-list-create").performClick()
        rule.waitUntil(15_000) { nodes("list-title").isNotEmpty() }
        rule.onNodeWithTag("list-title").performTextInput("Device list")
        rule.onNodeWithTag("list-save").performClick()
        rule.waitUntil(30_000) { nodes("profile-menu").isNotEmpty() } // back on alice's profile
        val list = runBlocking { ready.lists.own().first() }.single { it.title == "Device list" }
        assertEquals(listOf(alice.publicKey().toHex()), list.members)
        assertTrue(list.private)

        // own profile → Lists → pin
        Espresso.pressBack()
        rule.waitUntil(15_000) { nodes("feed-profile").isNotEmpty() }
        rule.onNodeWithTag("feed-profile").performClick()
        rule.waitUntil(15_000) { nodes("profile-tab-lists").isNotEmpty() }
        rule.onNodeWithTag("profile-tab-lists").performClick()
        rule.waitUntil(30_000) { nodes("list-row").isNotEmpty() }
        rule.onNodeWithTag("list-pin").performClick()
        rule.waitUntil(30_000) { runBlocking { ready.feedTabs.tabs().first() } == listOf(list.address) }

        // the tab shows the list's notes
        Espresso.pressBack()
        rule.waitUntil(15_000) { nodes("feed-tabs").isNotEmpty() }
        rule.onNodeWithTag("feed-tab-1").performClick()
        rule.waitUntil(30_000) { textShown(aliceNote.content()) }

        // long press → Unpin: the row goes
        rule.onNodeWithTag("feed-tab-1").performTouchInput { longClick() }
        rule.waitUntil(5_000) { nodes("feed-tab-menu-unpin").isNotEmpty() }
        rule.onNodeWithTag("feed-tab-menu-unpin").performClick()
        rule.waitUntil(30_000) { nodes("feed-tabs").isEmpty() }

        // logout removes the account's local tabs copy (spec §3.3)
        val pk = ready.pubkey
        runBlocking { entry.session().logout() }
        assertNull(runBlocking { entry.feedTabs().load(pk) })
    }

    @Test
    fun pinAForeignListFromItsAuthorsProfile() {
        val people = seed("bob-lists", "carol-lists")
        val (bob, bobNote) = people[0]
        val (carol, carolNote) = people[1]
        val ready = entry.session().requireReady()
        val picks = EventBuilder(Kind(30000u), "")
            .tags(listOf(Tag.parse(listOf("d", "picks")), Tag.parse(listOf("title", "Bob picks")), Tag.parse(listOf("p", carol.publicKey().toHex()))))
            .signWithKeys(bob)
        runBlocking { ready.engine.save(picks) }
        openProfileOf(bobNote)
        rule.onNodeWithTag("profile-tab-lists").performClick()
        rule.waitUntil(30_000) { textShown("Bob picks") }
        rule.onNodeWithTag("list-pin").performClick()
        rule.waitUntil(30_000) { runBlocking { ready.feedTabs.tabs().first() }.any { it.d == "picks" } }
        Espresso.pressBack()
        rule.waitUntil(15_000) { nodes("feed-tabs").isNotEmpty() }
        rule.onNodeWithTag("feed-tab-1").performClick()
        rule.waitUntil(30_000) { textShown(carolNote.content()) }
    }
}
