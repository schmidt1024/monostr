package com.monostr.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.model.AddressRef
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

/** A large «Follow all» through a follow pack: a question first, one contact list write, «Undo» takes back only those accounts. */
@RunWith(AndroidJUnit4::class)
class FollowAllTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val bob = Keys.generate()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    private fun follows(): Set<String> = runBlocking {
        entry.session().requireReady().engine.query(Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u))
    }.firstOrNull()?.tags()?.toVec()?.map { it.asVec() }?.filter { it[0] == "p" }?.map { it[1] }?.toSet() ?: emptySet()

    @Test
    fun aLargeFollowAllAsksFirstAndUndoTakesBackOnlyThoseAccounts() {
        val ts = System.currentTimeMillis()
        val d = "pack-$ts"
        val members = (0 until 12).map { Keys.generate().publicKey().toHex() }
        val pack = EventBuilder(Kind(39089u), "")
            .tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", "Pack $ts"))) + members.map { Tag.parse(listOf("p", it)) })
            .signWithKeys(bob)
        val note = EventBuilder.textNote("my pack $ts\n" + AddressRef(39089, bob.publicKey().toHex(), d).toBech32()).signWithKeys(alice)
        val mine = EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me)
        val old = setOf(alice.publicKey().toHex())
        LoopbackRelay(serve = listOf(mine, pack, note).map { it.asJson() }).use { relay ->
            runBlocking {
                runCatching { entry.session().logout() }
                entry.session().applyRelays(listOf(relay.url))
            }
            rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() }
            rule.onNodeWithTag("login-secret").performTextInput(me.secretKey().toHex())
            rule.onNodeWithTag("login-secret-button").performSemanticsAction(SemanticsActions.OnClick)
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
            if (nodes("setup-skip").isNotEmpty()) {
                rule.onNodeWithTag("setup-skip").performClick()
                rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
            }
            val ready = entry.session().requireReady()
            runBlocking { listOf(mine, pack, note).forEach { ready.engine.save(it) } }

            val card = "address-card-${d.take(8)}"
            rule.waitUntil(30_000) { rule.onAllNodesWithText("my pack $ts").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(card))
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Pack $ts").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(card).performTouchInput { click(Offset(centerX, height - 4.dp.toPx())) }
            rule.waitUntil(15_000) { nodes("list-tab-members").isNotEmpty() }
            rule.onNodeWithTag("list-tab-members").performClick()

            rule.waitUntil(15_000) { nodes("list-follow-all").isNotEmpty() }
            rule.onNodeWithTag("list-follow-all").performClick()
            rule.waitUntil(5_000) { nodes("follow-all-confirm").isNotEmpty() }
            assertEquals("the question comes before any write", old, follows())
            rule.onNodeWithTag("follow-all-yes").performClick()
            rule.waitUntil(15_000) { follows() == old + members }

            val undo = ctx.getString(R.string.mute_undo)
            rule.waitUntil(5_000) { rule.onAllNodesWithText(undo).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(undo).performClick()
            rule.waitUntil(15_000) { follows() == old }
        }
    }
}
