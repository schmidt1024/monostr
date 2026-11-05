package com.monostr.app

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.model.RelayMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.time.Duration

/**
 * Spec 2026-11-05 (relay read and write): a write-only relay gets the note and only requests for the user's own
 * events, a read-only relay gets requests for the followed author and no note; the same on a new engine after a cold
 * start, which has only the stored modes, and the row still shows them.
 * The write-only mode is chosen through the real Settings row, the read-only one too.
 */
@RunWith(AndroidJUnit4::class)
class RelayModesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().resetRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    private fun openSettings() {
        rule.onNodeWithContentDescription(ctx.getString(R.string.feed_settings)).performClick()
    }

    private fun chooseMode(url: String, mode: RelayMode) {
        rule.waitUntil(15_000) { nodes("relay-mode-$url").isNotEmpty() }
        rule.onNodeWithTag("relay-mode-$url").performScrollTo().performClick()
        rule.waitUntil(5_000) { nodes("relay-mode-$url-$mode").isNotEmpty() }
        rule.onNodeWithTag("relay-mode-$url-$mode").performClick()
        val label = ctx.getString(when (mode) { RelayMode.BOTH -> R.string.relay_mode_both; RelayMode.READ -> R.string.relay_mode_read; RelayMode.WRITE -> R.string.relay_mode_write })
        assertModeShown(url, label)
    }

    private fun assertModeShown(url: String, label: String) {
        rule.waitForIdle()
        rule.onNodeWithTag("relay-mode-$url").performScrollTo()
            .assertContentDescriptionEquals(ctx.getString(R.string.relay_mode_description, url.removePrefix("wss://"), label))
    }

    private fun filters(req: String): List<org.json.JSONObject> {
        val a = JSONArray(req)
        return (2 until a.length()).map { a.getJSONObject(it) }
    }

    private fun authors(f: org.json.JSONObject): List<String>? = f.optJSONArray("authors")?.let { j -> (0 until j.length()).map { j.getString(it) } }

    /** One read about another author and one of the user's own events, from whatever engine runs now. */
    private fun readAliceAndOwnProfile() = runBlocking {
        val ready = entry.session().requireReady()
        ready.engine.fetch(Filter().kind(Kind(1u)).author(alice.publicKey()).limit(5u), timeout = Duration.ofSeconds(4))
        ready.profiles.freshMetadata(me.publicKey().toHex(), Duration.ofSeconds(4))
    }

    /** The write-only relay really was asked for the own events and for nothing else; the read-only one for the followed author. */
    private fun assertRouting(moment: String, writeReqs: List<String>, readReqs: List<String>, meHex: String, aliceHex: String) {
        // a DM inbox subscription is the one explicit request a main-list relay may carry (spec: DM delivery unchanged)
        val requests = writeReqs.flatMap { filters(it) }
        val bad = requests.filter { authors(it) != listOf(meHex) && !isOwnInbox(it, meHex) }.map { it.toString() }
        assertEquals("$moment: every filter on the write-only relay has the user as only author: $bad", emptyList<String>(), bad)
        assertTrue("$moment: the write-only relay got the own-events request: $writeReqs", requests.any { f -> authors(f) == listOf(meHex) && f.optJSONArray("kinds")?.optInt(0) == 0 })
        assertTrue("$moment: the read-only relay saw a REQ naming the followed author: $readReqs", readReqs.any { req -> filters(req).any { aliceHex in (authors(it) ?: emptyList()) } })
    }

    private fun isOwnInbox(f: org.json.JSONObject, meHex: String): Boolean =
        f.optJSONArray("kinds")?.optInt(0) == 1059 && f.optJSONArray("#p")?.optString(0) == meHex

    @Test
    fun writeOnlyGetsTheNoteAndOwnRequestsReadOnlyGetsFollowRequestsAndNoNote() {
        val ts = System.currentTimeMillis()
        val meHex = me.publicKey().toHex()
        val aliceHex = alice.publicKey().toHex()
        val mine = EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me)
        LoopbackRelay(serve = listOf(mine.asJson())).use { writeOnly ->
            LoopbackRelay(serve = listOf(mine.asJson())).use { readOnly ->
                runBlocking {
                    runCatching { entry.session().logout() }
                    entry.session().applyRelays(listOf(writeOnly.url, readOnly.url))
                }
                rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() }
                rule.onNodeWithTag("login-secret").performTextInput(me.secretKey().toHex())
                rule.onNodeWithTag("login-secret-button").performClick()
                rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
                if (nodes("setup-skip").isNotEmpty()) {
                    rule.onNodeWithTag("setup-skip").performClick()
                    rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
                }
                runBlocking { entry.session().requireReady().engine.save(mine) }

                openSettings()
                chooseMode(writeOnly.url, RelayMode.WRITE)
                chooseMode(readOnly.url, RelayMode.READ)
                // everything before this point may legitimately have gone anywhere
                val writeReqsBefore = writeOnly.reqs.size
                val readReqsBefore = readOnly.reqs.size
                val writeEventsBefore = writeOnly.events.size
                val readEventsBefore = readOnly.events.size

                // a fresh read of the followed author's notes after the modes were set (the feed's own subscriptions were opened before)
                readAliceAndOwnProfile()

                Espresso.pressBack()
                rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
                rule.onNodeWithTag("fab-compose").performClick()
                rule.waitUntil(10_000) { nodes("compose-text").isNotEmpty() }
                rule.onNodeWithTag("compose-text").performTextInput("modes note $ts")
                rule.onNodeWithTag("compose-send").performClick()
                rule.waitUntil(20_000) { writeOnly.events.drop(writeEventsBefore).any { it.contains("modes note $ts") } }
                Thread.sleep(3_000) // a stray write or request would have arrived by now

                assertTrue("the write-only relay has the note", writeOnly.events.drop(writeEventsBefore).any { it.contains("modes note $ts") })
                assertEquals("the read-only relay got no EVENT: ${readOnly.events.drop(readEventsBefore)}", 0, readOnly.events.size - readEventsBefore)

                val writeReqs = writeOnly.reqs.drop(writeReqsBefore)
                val readReqs = readOnly.reqs.drop(readReqsBefore)
                assertRouting("after the mode", writeReqs, readReqs, meHex, aliceHex)

                // a cold start: a new engine that has only the stored modes (a recreated activity keeps the old engine)
                val writeReqsCold = writeOnly.reqs.size
                val readReqsCold = readOnly.reqs.size
                val writeEventsCold = writeOnly.events.size
                val readEventsCold = readOnly.events.size
                val engineBefore = entry.session().requireReady().engine
                runBlocking {
                    entry.session().release()
                    entry.session().restore()
                }
                assertTrue("a new engine", entry.session().requireReady().engine !== engineBefore)
                rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() }
                readAliceAndOwnProfile()
                runBlocking { entry.session().requireReady().publish.post("cold note $ts") }
                rule.waitUntil(20_000) { writeOnly.events.drop(writeEventsCold).any { it.contains("cold note $ts") } }
                Thread.sleep(3_000)
                assertEquals("the read-only relay got no EVENT after the cold start: ${readOnly.events.drop(readEventsCold)}", 0, readOnly.events.size - readEventsCold)
                assertRouting("after the cold start", writeOnly.reqs.drop(writeReqsCold), readOnly.reqs.drop(readReqsCold), meHex, aliceHex)

                // and the row shows the modes
                openSettings()
                rule.waitUntil(15_000) { nodes("relay-mode-${writeOnly.url}").isNotEmpty() }
                assertModeShown(writeOnly.url, ctx.getString(R.string.relay_mode_write))
                assertModeShown(readOnly.url, ctx.getString(R.string.relay_mode_read))
            }
        }
    }
}
