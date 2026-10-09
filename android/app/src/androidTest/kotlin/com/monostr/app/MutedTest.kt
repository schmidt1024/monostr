package com.monostr.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata

/** Spec 11: muting from the menu with Undo, the settings list, the muted profile. Needs network (the list goes to the default relays). */
@RunWith(AndroidJUnit4::class)
class MutedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    /** A fresh key per run: the kind 10000 this test publishes to the real relays never lands on a shared, well-known account. */
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun str(id: Int) = ctx.getString(id)
    private fun str(id: Int, arg: String) = ctx.getString(id, arg)
    private fun textShown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // the session must not stay Active for whatever test class the runner picks next in the same process
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

    private fun show(vararg events: Event) {
        val ready = entry.session().requireReady()
        runBlocking { events.forEach { ready.engine.save(it) } }
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
    }

    /** Logged in, following alice (named [name]) who wrote one note; the note is on screen. */
    private fun seedAlice(name: String): Triple<Keys, Event, String> {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val note = EventBuilder.textNote("Mute me ${System.currentTimeMillis()}").signWithKeys(alice)
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"$name"}""")).signWithKeys(alice))
        }
        show(note)
        rule.waitUntil(30_000) { textShown(note.content()) }
        return Triple(alice, note, name)
    }

    private fun muteFromTheMenu(note: Event, name: String) {
        rule.onNodeWithTag("note-menu-${note.id().toHex().take(8)}").performClick()
        rule.onNodeWithTag("note-menu-mute").performClick()
        rule.waitUntil(30_000) { textShown(str(R.string.mute_done, name)) }
        rule.waitUntil(15_000) { !textShown(note.content()) }
    }

    private fun openMutedList() {
        rule.onNodeWithContentDescription(str(R.string.feed_settings)).performClick()
        rule.waitUntil(15_000) { nodes("settings-muted").isNotEmpty() }
        rule.onNodeWithTag("settings-muted").performScrollTo()
        rule.onNodeWithTag("settings-muted").performClick()
        rule.waitUntil(15_000) { nodes("muted-list").isNotEmpty() }
    }

    @Test
    fun muteFromTheMenuHidesTheCardAndUndoBringsItBack() {
        val (alice, note, name) = seedAlice("alice-mute-test")
        val ready = entry.session().requireReady()
        val key = alice.publicKey().toHex()

        muteFromTheMenu(note, name)
        assertTrue(key in ready.mute.muted.value)
        rule.onNodeWithTag("mute-undo").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.mute_undo)).performClick()
        rule.waitUntil(30_000) { textShown(note.content()) }
        assertTrue(key !in ready.mute.muted.value)

        muteFromTheMenu(note, name)
        // the list is stored locally once a relay accepted it: the entry is private, so no public p tag, but an encrypted content
        rule.waitUntil(30_000) { runBlocking { ready.engine.query(Filter().kind(Kind(10000u)).author(me.publicKey())) }.isNotEmpty() }
        val lists = runBlocking { ready.engine.query(Filter().kind(Kind(10000u)).author(me.publicKey())) }
        val latest = lists.maxByOrNull { it.createdAt().asSecs() }!!
        assertTrue("alice is a public p tag of the mute list", latest.tags().toVec().none { it.asVec().firstOrNull() == "p" })
        assertTrue("the private part is empty", latest.content().isNotEmpty())

        openMutedList()
        rule.waitUntil(15_000) { textShown(name) }
        rule.onNodeWithTag("muted-unmute-${key.take(8)}").performClick()
        rule.waitUntil(15_000) { nodes("muted-empty").isNotEmpty() }
        assertTrue(key !in ready.mute.muted.value)
        // back at once, before a relay answered: the write runs on the session's scope and still goes out
        rule.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        rule.waitUntil(15_000) { nodes("settings-muted").isNotEmpty() }
        rule.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        rule.waitUntil(15_000) { nodes("feed-refresh").isNotEmpty() }
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
        rule.waitUntil(30_000) { textShown(note.content()) }
        assertTrue(key !in ready.mute.muted.value)
        // and the unmute reached a relay: a newer list is stored
        rule.waitUntil(30_000) {
            runBlocking { ready.engine.query(Filter().kind(Kind(10000u)).author(me.publicKey())) }.maxOf { it.createdAt().asSecs() } > latest.createdAt().asSecs()
        }
    }

    @Test
    fun aMutedProfileShowsTheHintAndItsNotes() {
        val (alice, note, name) = seedAlice("alice-profile-mute")
        val key = alice.publicKey().toHex()
        muteFromTheMenu(note, name)

        // the muted-accounts row opens the profile
        openMutedList()
        rule.waitUntil(15_000) { textShown(name) }
        rule.onNodeWithText(name).performClick()
        rule.waitUntil(15_000) { nodes("profile-muted-hint").isNotEmpty() }
        rule.onNodeWithTag("profile-muted-hint").assertIsDisplayed()
        // the profile still shows her notes
        rule.waitUntil(30_000) { textShown(note.content()) }
        rule.onNodeWithTag("profile-unmute").performClick()
        rule.waitUntil(15_000) { nodes("profile-muted-hint").isEmpty() }
        assertTrue(key !in entry.session().requireReady().mute.muted.value)
        assertEquals(0, nodes("profile-unmute").size)
    }
}
